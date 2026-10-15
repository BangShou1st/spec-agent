package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件名:OpenCodeZenMetadataCatalog.java
 *
 * 用途:从官方 OpenCode 模型目录({@code https://models.opencode.ai/api.json})读取
 * {@code opencode} 提供商的模型元数据,作为"免费判定 + 协议判定 + 下线判定"的事实来源。
 *
 * <p>为什么不能只看模型 id 后缀:线上目录里免费模型并不都以 {@code -free} 结尾
 * (例如 {@code big-pickle} 的价格元数据是 input=0/output=0,是免费模型),而某些
 * 带 {@code -free} 的模型其实是别的协议(例如 {@code muse-spark-1.3-contributor-free}
 * 的 {@code provider.npm} 是 {@code @ai-sdk/openai},走 Responses 端点)。
 * 用后缀当唯一判据会同时产生两类错误:漏掉真免费模型、把付费/异协议模型放行。
 *
 * <p><b>失败语义</b>:任何获取/解析失败都返回 {@link View#unavailable()},并且:
 * <ul>
 *   <li>不把"没有价格字段"当成免费——只有明确 input=0 且 output=0 才是免费;</li>
 *   <li>保留上一次成功视图(若有),刷新失败不会让正在工作的会话瞬时失去目录;</li>
 *   <li>失败同样按 TTL 节流,不对上游做密集重试。</li>
 * </ul>
 *
 * <p>本类只读公开目录,不携带、不转发任何用户凭据;请求体上限 {16 MiB},超时与代理
 * 策略与 Zen 传输层一致(默认 DIRECT,不继承 JVM/系统默认 {@code ProxySelector})。
 */
@Component
public class OpenCodeZenMetadataCatalog implements OpenCodeZenModelMetadataSource {

    static final String DEFAULT_METADATA_URL = "https://models.opencode.ai/api.json";
    private static final Logger LOG = LoggerFactory.getLogger(OpenCodeZenMetadataCatalog.class);
    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    private static final List<String> RETIRED_STATUSES =
            List.of("deprecated", "retired", "retiring", "disabled");
    private static final List<String> RETIREMENT_DATE_FIELDS =
            List.of("deprecation_date", "retired_at", "deprecated_at", "retirement_date");

    private final ObjectMapper mapper;
    private final String metadataUrl;
    private final Duration timeout;
    private final long ttlSeconds;
    private final HttpClient client;
    private final Object refreshLock = new Object();

    private volatile Cached cached = new Cached(View.unavailable(), 0L);

    @Autowired
    public OpenCodeZenMetadataCatalog(ObjectMapper mapper,
            @Value("${spec.agent.model.opencode.metadata-url:" + DEFAULT_METADATA_URL + "}") String metadataUrl,
            @Value("${spec.agent.model.opencode.metadata-timeout-seconds:20}") long timeoutSeconds,
            @Value("${spec.agent.model.opencode.metadata-ttl-seconds:3600}") long ttlSeconds,
            @Value("${spec.agent.model.opencode.proxy:DIRECT}") String proxyConfig) {
        this(mapper, metadataUrl, timeoutSeconds, ttlSeconds,
                HttpClient.newBuilder().proxy(HttpOpenCodeZenTransport.proxySelectorFor(proxyConfig)).build());
    }

    /** 确定性测试入口:直接注入客户端与元数据地址。 */
    OpenCodeZenMetadataCatalog(ObjectMapper mapper, String metadataUrl, long timeoutSeconds,
                               long ttlSeconds, HttpClient client) {
        this.mapper = mapper;
        this.metadataUrl = metadataUrl == null || metadataUrl.isBlank()
                ? DEFAULT_METADATA_URL : metadataUrl.trim();
        this.timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        this.client = client;
    }

    @Override
    public View view() {
        Cached current = cached;
        if (!stale(current)) {
            return current.view();
        }
        synchronized (refreshLock) {
            if (cached == current) {
                refresh();
            }
            return cached.view();
        }
    }

    private boolean stale(Cached snapshot) {
        return System.currentTimeMillis() - snapshot.loadedAtMillis() >= ttlSeconds * 1000L;
    }

    private void refresh() {
        View fetched = fetch();
        View previous = cached.view();
        if (!fetched.available() && previous.available()) {
            // 保留上一次成功视图:刷新失败只影响新鲜度,不影响正在工作的目录。
            LOG.warn("OpenCode model metadata refresh failed; keeping the previous verified catalog");
            cached = new Cached(previous, System.currentTimeMillis());
            return;
        }
        if (!fetched.available()) {
            LOG.warn("OpenCode model metadata unavailable; free/protocol decisions fall back to the conservative rule");
        }
        cached = new Cached(fetched, System.currentTimeMillis());
    }

    private View fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metadataUrl))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", "spec-agent/opencode-metadata")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return View.unavailable();
                }
                byte[] raw = readBounded(body);
                if (raw == null) {
                    return View.unavailable();
                }
                return parse(raw);
            }
        } catch (IOException ex) {
            LOG.warn("OpenCode model metadata request failed: {}", ex.getClass().getSimpleName());
            return View.unavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return View.unavailable();
        } catch (RuntimeException ex) {
            LOG.warn("OpenCode model metadata could not be parsed: {}", ex.getClass().getSimpleName());
            return View.unavailable();
        }
    }

    private View parse(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException ex) {
            return View.unavailable();
        }
        JsonNode provider = root == null ? null : root.get("opencode");
        if (provider == null || !provider.isObject()) {
            return View.unavailable();
        }
        OpenCodeModelProtocol providerProtocol =
                OpenCodeModelProtocol.fromNpmPackage(text(provider.get("npm")));
        JsonNode models = provider.get("models");
        if (models == null || !models.isObject()) {
            return View.unavailable();
        }
        Map<String, OpenCodeZenModelMetadata> byId = new LinkedHashMap<>();
        models.fieldNames().forEachRemaining(id -> {
            JsonNode entry = models.get(id);
            if (entry != null && entry.isObject()) {
                byId.put(id, metadataFor(id, entry, providerProtocol));
            }
        });
        return new View(true, byId);
    }

    private OpenCodeZenModelMetadata metadataFor(String id, JsonNode entry,
                                                 OpenCodeModelProtocol providerProtocol) {
        JsonNode cost = entry.get("cost");
        // 只有明确 input=0 且 output=0 才算免费;缺字段或非数值一律按"未证明免费"处理。
        boolean free = cost != null && cost.isObject()
                && isZero(cost.get("input")) && isZero(cost.get("output"));
        JsonNode modelNpm = entry.path("provider").path("npm");
        OpenCodeModelProtocol protocol = modelNpm.isTextual() && !modelNpm.asText().isBlank()
                ? OpenCodeModelProtocol.fromNpmPackage(modelNpm.asText())
                : providerProtocol;
        return new OpenCodeZenModelMetadata(id, free, isRetired(entry), protocol);
    }

    private static boolean isZero(JsonNode value) {
        return value != null && value.isNumber() && value.asDouble() == 0d;
    }

    private static boolean isRetired(JsonNode entry) {
        if (entry.path("deprecated").asBoolean(false)) {
            return true;
        }
        for (String field : RETIREMENT_DATE_FIELDS) {
            if (entry.hasNonNull(field)) {
                return true;
            }
        }
        String status = entry.path("status").asText("").trim().toLowerCase(Locale.ROOT);
        return RETIRED_STATUSES.contains(status);
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }

    /** 有界读取:超过上限返回 null(整体视为不可用),绝不解析半截报文。 */
    private static byte[] readBounded(InputStream body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = body.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (output.size() + read > MAX_METADATA_BYTES) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private record Cached(View view, long loadedAtMillis) {
    }
}
