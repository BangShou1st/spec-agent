package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:HttpOpenCodeZenTransportUserAgentTest.java
 *
 * 测试目标:验证 HttpOpenCodeZenTransport 对所有 OpenCode HTTP 路径(/models、
 * /chat/completions)都携带产品 User-Agent 与桌面客户端身份头集(CLIENT_ID、
 * msg_ 前缀的请求 ID、全局项目 ID);同一会话跨多个请求时消息 ID 必须保持唯一。
 */
class HttpOpenCodeZenTransportUserAgentTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> userAgents = new ArrayList<>();
    private final List<String> clients = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> projects = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void everyOpenCodeHttpPathCarriesTheProductUserAgent() {
        HttpOpenCodeZenTransport transport = new HttpOpenCodeZenTransport(
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                5, "DIRECT");

        transport.listModels(null);
        transport.validateCredential("probe-key", "alpha-free");
        transport.complete("completion-key", "ses_useragentprobe01", new OpenCodeChatCompletionRequest(
                "alpha-free",
                List.of(new OpenCodeChatMessage("user", "hello"))));

        assertThat(paths).containsExactly("/models", "/chat/completions", "/chat/completions");
        assertThat(userAgents)
                .hasSize(3)
                .allSatisfy(userAgent -> assertThat(userAgent)
                        .isNotBlank()
                        .isEqualTo(OpenCodeZenTransport.USER_AGENT));
        assertThat(userAgents).allMatch(userAgent -> userAgent.equals(userAgents.get(0)));
    }

    @Test
    void everyZenHttpRequestCarriesTheDesktopIdentitySet() {
        HttpOpenCodeZenTransport transport = new HttpOpenCodeZenTransport(
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                5, "DIRECT");

        transport.listModels(null);
        transport.validateCredential("probe-key", "alpha-free");
        transport.complete("completion-key", "ses_identityprobe0001", new OpenCodeChatCompletionRequest(
                "alpha-free",
                List.of(new OpenCodeChatMessage("user", "hello"))));
        transport.complete("completion-key", "ses_identityprobe0001", new OpenCodeChatCompletionRequest(
                "alpha-free",
                List.of(new OpenCodeChatMessage("user", "again"))));

        assertThat(paths).hasSize(4);
        assertThat(clients).hasSize(4)
                .allSatisfy(client -> assertThat(client).isEqualTo(OpenCodeZenTransport.CLIENT_ID));
        assertThat(requests).hasSize(4)
                .allSatisfy(id -> assertThat(id)
                        .isNotBlank()
                        .hasSize(4 + 26)
                        .startsWith("msg_")
                        .matches("[0-9a-zA-Z_]+"));
        assertThat(projects).hasSize(4)
                .allSatisfy(id -> assertThat(id).isEqualTo(OpenCodeZenTransport.GLOBAL_PROJECT));
        // 一个会话横跨多个请求:每个请求仍要拿到全新的消息 ID。
        assertThat(requests).doesNotHaveDuplicates();
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        clients.add(exchange.getRequestHeaders().getFirst(OpenCodeZenTransport.CLIENT_HEADER));
        requests.add(exchange.getRequestHeaders().getFirst(OpenCodeZenTransport.REQUEST_HEADER));
        projects.add(exchange.getRequestHeaders().getFirst(OpenCodeZenTransport.PROJECT_HEADER));
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String body = exchange.getRequestURI().getPath().equals("/models")
                ? "{\"data\":[{\"id\":\"alpha-free\"}]}"
                : requestBody.contains("\"stream\":true")
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"action\\\":\\\"finish\\\",\\\"output\\\":{}}\"}}]}\n\ndata: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"finish\\\",\\\"output\\\":{}}\"}}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                body.startsWith("data:") ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
