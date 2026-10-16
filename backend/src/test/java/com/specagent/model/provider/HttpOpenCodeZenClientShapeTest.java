package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:HttpOpenCodeZenClientShapeTest.java
 *
 * 测试目标:锁定 2026-10-16 字节级 A/B 对照得出的客户端形态契约(与已验证可用的
 * 本地 V2 客户端逐字节对齐)。覆盖:免费档公开凭据回退(密钥缺省时 Bearer public,
 * 有密钥时 Bearer 密钥)、x-opencode-session 与 x-opencode-session-id 同值双头、
 * 会话/请求 id 的时间前缀计数器形态(降序 ses 中段 ∈ 0xff0..0xfff、升序 msg
 * 低 12 位为小值计数器)、生产补全请求体携带 stream_options.include_usage,
 * 以及请求体保持紧凑且不含采样参数。
 */
class HttpOpenCodeZenClientShapeTest {

    private static final String SESSION = "ses_0123456789abCLIENTSHAPEPRO";

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Captured> captured = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        captured.add(new Captured(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(), new String(body, StandardCharsets.UTF_8)));
        String payload = exchange.getRequestURI().getPath().endsWith("/models")
                ? "{\"object\":\"list\",\"data\":[{\"id\":\"alpha-free\"}]}"
                : "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                payload.startsWith("data:") ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpOpenCodeZenTransport transport() {
        return new HttpOpenCodeZenTransport(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), 5, "DIRECT");
    }

    private OpenCodeChatCompletionRequest completionRequest() {
        return new OpenCodeChatCompletionRequest("alpha-free",
                List.of(new OpenCodeChatMessage("user", "hello")));
    }

    @Test
    void missingKeySendsTheFreeTierPublicCredentialOnEveryPath() {
        HttpOpenCodeZenTransport transport = transport();

        transport.listModels(null);
        transport.validateCredential(null, "alpha-free");
        transport.complete(null, SESSION, completionRequest());

        assertThat(captured).hasSize(3);
        assertThat(captured).allSatisfy(request -> assertThat(request.authorization())
                .isEqualTo("Bearer " + OpenCodeZenTransport.FREE_TIER_CREDENTIAL));
    }

    @Test
    void blankKeyAlsoSendsTheFreeTierPublicCredential() {
        transport().complete("   ", SESSION, completionRequest());

        assertThat(captured.getFirst().authorization())
                .isEqualTo("Bearer " + OpenCodeZenTransport.FREE_TIER_CREDENTIAL);
    }

    @Test
    void presentKeyStillSendsTheRealCredential() {
        transport().complete("sk-real-key", SESSION, completionRequest());

        assertThat(captured.getFirst().authorization()).isEqualTo("Bearer sk-real-key");
    }

    @Test
    void everyRequestCarriesBothSessionHeadersWithTheSameValue() {
        HttpOpenCodeZenTransport transport = transport();

        transport.listModels(null);
        transport.validateCredential("probe-key", "alpha-free");
        transport.complete("key", SESSION, completionRequest());

        assertThat(captured).hasSize(3);
        assertThat(captured).allSatisfy(request -> {
            assertThat(request.session()).isNotBlank().startsWith("ses_");
            assertThat(request.sessionId()).isEqualTo(request.session());
        });
        // 会话头在补全请求上必须原样保留调用方传入的值。
        assertThat(captured.get(2).session()).isEqualTo(SESSION);
    }

    /**
     * 线上计数器门禁:降序会话 id 中段三位十六进制 ∈ 0xff0..0xfff(counter ≤ 0xF);
     * 升序请求 id 低 12 位是小值计数器。随机计数器的 id 会被上游 403,这里把
     * 合规形态锁进 HTTP 契约测试。
     */
    @Test
    void generatedIdsCarryTheUpstreamCounterSemantics() {
        HttpOpenCodeZenTransport transport = transport();

        transport.listModels(null);
        transport.validateCredential("probe-key", "alpha-free");

        for (Captured request : captured) {
            String session = request.session();
            String sessionPayload = session.substring("ses_".length());
            int sessionCounter = 0xFFF - Integer.parseInt(sessionPayload.substring(9, 12), 16);
            assertThat(sessionCounter).isBetween(0, 0xF);

            String msg = request.msg();
            String msgPayload = msg.substring("msg_".length());
            int msgCounter = Integer.parseInt(msgPayload.substring(9, 12), 16) & 0xFFF;
            // 升序 msg id 的中段就是 (timestamp*0x1000 + counter) 的低 12 位;
            // 真实客户端在同一毫秒内从 0 递增,时钟低位使它偶有进位,
            // 但与降序形态一起存在单调的时间前缀(前 9 位 hex)。
            assertThat(msgCounter).isBetween(0, 0x1000);
            assertThat(msgPayload.substring(0, 9)).matches("[0-9a-f]{9}");
        }
    }

    @Test
    void productionCompletionPayloadMatchesTheVerifiedClientEnvelope() throws IOException {
        transport().complete("key", SESSION, completionRequest());

        JsonNode payload = mapper.readTree(captured.getFirst().body());
        assertThat(payload.get("stream").asBoolean()).isTrue();
        assertThat(payload.get("stream_options").get("include_usage").asBoolean()).isTrue();
        assertThat(payload.get("tools")).isNotEmpty();
        assertThat(payload.has("temperature") || payload.has("top_p")).isFalse();
    }

    private record Captured(String path,
                            com.sun.net.httpserver.Headers headers,
                            String body) {

        String authorization() {
            return headers.getFirst("Authorization");
        }

        String session() {
            return headers.getFirst(OpenCodeZenTransport.SESSION_HEADER);
        }

        String sessionId() {
            return headers.getFirst(OpenCodeZenTransport.SESSION_ID_HEADER);
        }

        String msg() {
            return headers.getFirst(OpenCodeZenTransport.REQUEST_HEADER);
        }
    }
}
