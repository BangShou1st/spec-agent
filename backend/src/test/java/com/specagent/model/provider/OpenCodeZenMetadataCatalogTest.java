package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:OpenCodeZenMetadataCatalogTest.java
 *
 * 测试目标:官方模型目录元数据的解析与失败语义(全部针对本机 stub 服务器,绝不触网)。
 *
 * <p>覆盖真实端点已核对的形态:{@code opencode} 提供商级别 {@code npm} 作为缺省协议、
 * 模型级 {@code provider.npm} 覆盖、{@code cost.input/output} 双零才算免费、
 * {@code status=deprecated} 视为已下线。失败语义:元数据不可用时视图标记为
 * {@code available=false} 且不登记任何模型——调用方因此绝不会把"没有价格"当成免费;
 * 刷新失败保留上一次成功视图,并按 TTL 节流重试。
 *
 * <p>报文样例按真实 {@code https://models.opencode.ai/api.json} 的 {@code opencode}
 * 子树结构构造(big-pickle 无 {@code -free} 后缀但价格为 0;muse-spark 系列
 * {@code provider.npm=@ai-sdk/openai};claude 系列为 {@code @ai-sdk/anthropic})。
 */
class OpenCodeZenMetadataCatalogTest {

    private static final String METADATA = """
            {"opencode":{"id":"opencode","npm":"@ai-sdk/openai-compatible",
              "models":{
                "big-pickle":{"cost":{"input":0,"output":0}},
                "cat-jam-free":{"cost":{"input":0,"output":0}},
                "half-priced-free":{"cost":{"input":0,"output":0.5}},
                "claude-opus-5":{"provider":{"npm":"@ai-sdk/anthropic"},"cost":{"input":5,"output":25}},
                "muse-spark-1.3-contributor-free":{"provider":{"npm":"@ai-sdk/openai"},"cost":{"input":0,"output":0}},
                "muse-spark-1.2-contributor-free":{"status":"deprecated","provider":{"npm":"@ai-sdk/openai"},
                  "cost":{"input":0,"output":0}},
                "no-price-model":{"provider":{"npm":"@ai-sdk/openai-compatible"}}
              }}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private int status = 200;
    private String body = METADATA;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.json", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private OpenCodeZenMetadataCatalog catalog(long ttlSeconds) {
        return new OpenCodeZenMetadataCatalog(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api.json",
                5, ttlSeconds, HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build());
    }

    @Test
    void freeRequiresExplicitZeroInputAndOutputPrices() {
        OpenCodeZenMetadataCatalog catalog = catalog(3600);
        var view = catalog.view();

        assertThat(view.available()).isTrue();
        assertThat(view.find("big-pickle").orElseThrow().free())
                .as("input=0 且 output=0 的免费模型即使没有 -free 后缀也必须被识别").isTrue();
        assertThat(view.find("cat-jam-free").orElseThrow().free()).isTrue();
        assertThat(view.find("half-priced-free").orElseThrow().free())
                .as("只有一项为 0 不算免费").isFalse();
        assertThat(view.find("claude-opus-5").orElseThrow().free()).isFalse();
        assertThat(view.find("no-price-model").orElseThrow().free())
                .as("缺少价格字段不等于免费").isFalse();
    }

    @Test
    void protocolComesFromModelNpmAndFallsBackToTheProviderPackage() {
        OpenCodeZenMetadataCatalog catalog = catalog(3600);
        var view = catalog.view();

        assertThat(view.find("big-pickle").orElseThrow().protocol())
                .as("模型级 npm 缺失时使用提供商级 @ai-sdk/openai-compatible").isEqualTo(OpenCodeModelProtocol.CHAT);
        assertThat(view.find("claude-opus-5").orElseThrow().protocol())
                .isEqualTo(OpenCodeModelProtocol.ANTHROPIC);
        assertThat(view.find("muse-spark-1.3-contributor-free").orElseThrow().protocol())
                .isEqualTo(OpenCodeModelProtocol.RESPONSES);
    }

    @Test
    void deprecatedStatusIsReportedAsRetired() {
        OpenCodeZenMetadataCatalog catalog = catalog(3600);
        var view = catalog.view();

        assertThat(view.find("muse-spark-1.2-contributor-free").orElseThrow().retired()).isTrue();
        assertThat(view.find("muse-spark-1.2-contributor-free").orElseThrow().chatExecutable()).isFalse();
        assertThat(view.find("muse-spark-1.3-contributor-free").orElseThrow().retired()).isFalse();
    }

    @Test
    void upstreamFailureIsUnavailableAndNeverInventModels() {
        status = 500;
        OpenCodeZenMetadataCatalog catalog = catalog(3600);

        var view = catalog.view();
        assertThat(view.available()).isFalse();
        assertThat(view.models()).isEmpty();
        assertThat(view.find("big-pickle")).isEmpty();
    }

    @Test
    void malformedOrUnrelatedPayloadIsUnavailable() {
        body = "{\"some-other-provider\":{\"models\":{\"x\":{}}}}";
        assertThat(catalog(3600).view().available()).isFalse();

        body = "not json at all";
        assertThat(catalog(3600).view().available()).isFalse();
    }

    @Test
    void ttlReusesTheLoadedViewAndRefreshFailureKeepsTheLastVerifiedCatalog() {
        OpenCodeZenMetadataCatalog catalog = catalog(3600);
        assertThat(catalog.view().find("big-pickle")).isPresent();
        assertThat(requests).hasValue(1);

        // 同一 TTL 窗口内不再请求上游。
        catalog.view();
        assertThat(requests).hasValue(1);

        // TTL 归零后刷新失败:保留上一次成功视图,且在 TTL 窗口内继续节流。
        OpenCodeZenMetadataCatalog expiring = catalog(0);
        assertThat(expiring.view().available()).isTrue();
        int afterFirst = requests.get();
        status = 503;
        assertThat(expiring.view().available())
                .as("刷新失败不得让已验证的目录瞬时消失").isTrue();
        assertThat(requests.get()).isEqualTo(afterFirst + 1);
    }
}
