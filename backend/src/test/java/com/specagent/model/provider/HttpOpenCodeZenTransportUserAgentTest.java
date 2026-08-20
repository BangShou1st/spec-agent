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

class HttpOpenCodeZenTransportUserAgentTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> userAgents = new ArrayList<>();

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
                5);

        transport.listModels(null);
        transport.validateCredential("probe-key", "alpha-free");
        transport.complete("completion-key", new OpenCodeChatCompletionRequest(
                "alpha-free",
                List.of(new OpenCodeChatMessage("user", "hello")),
                0.0));

        assertThat(paths).containsExactly("/models", "/chat/completions", "/chat/completions");
        assertThat(userAgents)
                .hasSize(3)
                .allSatisfy(userAgent -> assertThat(userAgent)
                        .isNotBlank()
                        .isEqualTo(OpenCodeZenTransport.USER_AGENT));
        assertThat(userAgents).allMatch(userAgent -> userAgent.equals(userAgents.get(0)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
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
