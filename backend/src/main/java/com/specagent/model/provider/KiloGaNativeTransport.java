package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.StreamCancelledException;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * 文件名:KiloGaNativeTransport.java
 *
 * 用途:Kilo Gateway 的原生 GA 工具调用传输(OpenAI 兼容 Chat Completions)。
 *
 * <p><b>为什么不直接用共享库的 {@code KiloGatewayClient.streamChat}</b>:该 API 自行完成
 * SSE 分帧并只回传解码后的 {@code JsonNode} 分片,且<b>在缺少终端 {@code [DONE]} 时仍按
 * 正常结束返回</b>(读到 EOF 即返回)。GA 的冻结规则要求"缺失终止信号、usage 或
 * {@code [DONE]} 一律终止请求",而这份严格判定与工具参数聚合已经存在于经过审计的
 * {@link GaNativeSseDecoder} 中。因此这里只做最小适配:沿用共享库已接入的
 * {@link KiloGatewayProperties}(base URL / 代理 / 超时),用 JDK HttpClient 取得原始 SSE
 * 流,再交给同一个审计过的解码器与 {@link GaChatCompletionsAdapter} 校验——绝不复刻
 * 一套安全关键的分片聚合逻辑,也绝不修改已发布的共享库版本。
 *
 * <p>不重试、不换模型、不换提供商:一次调用一次请求。凭据只在此处作为 Authorization
 * 头出现,绝不进入异常消息或日志。
 */
@Component
public class KiloGaNativeTransport {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;

    private final KiloGatewayProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public KiloGaNativeTransport(KiloGatewayProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.http = buildHttpClient(properties);
    }

    private static HttpClient buildHttpClient(KiloGatewayProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(properties.getConnectTimeout()))
                .followRedirects(HttpClient.Redirect.NEVER);
        String proxyHost = properties.getProxyHost();
        if (proxyHost != null && !proxyHost.isBlank()) {
            if (properties.getProxyPort() < 1 || properties.getProxyPort() > 65535) {
                throw new IllegalArgumentException("kilo.gateway.proxy-port must be 1..65535");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, properties.getProxyPort())));
        }
        return builder.build();
    }

    /** 非流式入口:内部同样走 SSE 聚合并完整校验,与已验证的原生路径保持一致。 */
    public GaModelContract.Response complete(GaModelTarget target, GaModelContract.Request request,
                                             Duration remainingBudget, BooleanSupplier active) {
        return stream(target, request, remainingBudget, active, text -> true);
    }

    public GaModelContract.Response stream(GaModelTarget target, GaModelContract.Request request,
                                           Duration remainingBudget, BooleanSupplier active,
                                           FragmentListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero()
                || remainingBudget.compareTo(Duration.ofSeconds(180)) > 0) {
            throw new IllegalArgumentException("Invalid GA execution deadline");
        }
        if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");

        URI endpoint = URI.create(baseUrl() + CHAT_COMPLETIONS_PATH);
        Map<String, Object> payload = new GaChatCompletionsAdapter().streamingRequestBody(request, target.model());
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid GA native payload");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json; charset=utf-8");
        if (target.hasCredential()) {
            requestBuilder.header("Authorization", "Bearer " + target.credential());
        }
        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        var streamRef = new AtomicReference<InputStream>();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ga-kilo-native");
            thread.setDaemon(true);
            return thread;
        });
        long started = System.nanoTime();
        var result = executor.submit(() -> {
            HttpResponse<InputStream> response = send(httpRequest);
            streamRef.set(response.body());
            try (InputStream stream = response.body()) {
                if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    // Read (and discard) a bounded body so the connection can be reused/closed;
                    // provider error text is never echoed into the exception message.
                    readBounded(stream);
                    throw errorFor(response.statusCode());
                }
                if (!contentType(response).startsWith("text/event-stream")) {
                    throw new IllegalArgumentException("GA native response must be SSE");
                }
                return new GaNativeSseDecoder().decode(stream, active, listener);
            }
        });
        try {
            while (true) {
                if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
                long remaining = remainingBudget.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) {
                    throw ModelProviderException.timeout("kilo", "GA native request deadline exceeded", null);
                }
                try {
                    var response = result.get(Math.min(remaining, Duration.ofMillis(100).toNanos()),
                            TimeUnit.NANOSECONDS);
                    if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
                    if (System.nanoTime() - started >= remainingBudget.toNanos()) {
                        throw ModelProviderException.timeout("kilo", "GA native request deadline exceeded", null);
                    }
                    return new GaChatCompletionsAdapter().validateResponse(request, response);
                } catch (TimeoutException pending) {
                    // Poll cancellation even when the provider sends no bytes.
                }
            }
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalArgumentException("GA native request failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StreamCancelledException("GA native request interrupted");
        } finally {
            result.cancel(true);
            InputStream stream = streamRef.get();
            if (stream != null) try {
                stream.close();
            } catch (IOException ignored) {
            }
            executor.shutdownNow();
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StreamCancelledException("GA native request interrupted");
        } catch (IOException io) {
            throw ModelProviderException.connection("kilo", "Kilo Gateway connection failed", io);
        }
    }

    private String baseUrl() {
        return properties.getBaseUrl().replaceAll("/+$", "");
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
    }

    private static void readBounded(InputStream stream) {
        try {
            stream.readNBytes(MAX_ERROR_BODY_BYTES);
        } catch (IOException ignored) {
            // The status code alone is the actionable signal; the body is never surfaced.
        }
    }

    private static ModelProviderException errorFor(int status) {
        if (status == 401 || status == 403) {
            return ModelProviderException.authentication("kilo", "Kilo Gateway rejected the credential", status);
        }
        if (status == 429) {
            return ModelProviderException.rateLimited("kilo", "Kilo Gateway rate limited the request");
        }
        if (status >= 500) {
            return ModelProviderException.serverError("kilo", "Kilo Gateway server error", status);
        }
        return ModelProviderException.providerRequestError("kilo", "Kilo Gateway rejected the request", status);
    }
}
