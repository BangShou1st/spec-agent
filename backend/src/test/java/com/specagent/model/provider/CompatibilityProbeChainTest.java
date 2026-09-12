package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The probe reuses the authoritative chain: parser then validator. */
class CompatibilityProbeChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String Q = String.valueOf((char) 34);
    private HttpServer server;
    private String baseUrl;
    private volatile String content;
    private CompatibilityProbeService probe;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            Map<String, Object> choice = Map.of(
                    "message", Map.of("content", content),
                    "finish_reason", "stop");
            Map<String, Object> body = Map.of("choices", List.of(choice));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        probe = new CompatibilityProbeService(new ObjectMapper(),
                new ProtocolAdapterRegistry(List.of(
                        new ChatCompletionsProtocolAdapter(),
                        new ResponsesProtocolAdapter(),
                        new AnthropicMessagesProtocolAdapter())),
                new GlobalAssistantDecisionParser(new ObjectMapper()),
                new GlobalAssistantDecisionValidator());
    }

    @AfterEach void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }

    private void probeContent(String c) {
        content = c;
        probe.probeCustom(CustomApiFormat.CHAT_COMPLETIONS, baseUrl, null, "m");
    }

    private static String finalJson(String assistantText) throws Exception {
        return MAPPER.writeValueAsString(Map.of("kind", "FINAL", "assistantText", assistantText));
    }

    @Test void validMinimalFinalPasses() throws Exception {
        String c = finalJson("probe ok");
        assertThatCode(() -> probeContent(c)).doesNotThrowAnyException();
    }

    @Test void duplicateKeyFails() {
        String c = "{" + Q + "kind" + Q + ":"
                + Q + "FINAL" + Q + ","
                + Q + "kind" + Q + ":"
                + Q + "FINAL" + Q + ","
                + Q + "assistantText" + Q + ":"
                + Q + "probe ok" + Q + "}";
        assertThatThrownBy(() -> probeContent(c)).isInstanceOf(ModelProviderException.class);
    }

    @Test void invalidFieldsFail() throws Exception {
        String c = MAPPER.writeValueAsString(
                Map.of("kind", "FINAL", "assistantText", "probe ok", "bogus", 1));
        assertThatThrownBy(() -> probeContent(c)).isInstanceOf(ModelProviderException.class);
    }

    @Test void validatorLayerFailureFails() throws Exception {
        String c = finalJson("   ");
        assertThatThrownBy(() -> probeContent(c)).isInstanceOf(ModelProviderException.class);
    }

    @Test void invalidUnicodeScalarFails() throws Exception {
        String lone = "a" + String.valueOf((char) 0xD800) + "b";
        String c = finalJson(lone);
        assertThatThrownBy(() -> probeContent(c)).isInstanceOf(ModelProviderException.class);
    }

    @Test void malformedShapeFails() {
        assertThatThrownBy(() -> probeContent("not json at all"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void nonFinalFails() throws Exception {
        String c = MAPPER.writeValueAsString(Map.of("kind", "CLARIFY", "assistantText", "which one?"));
        assertThatThrownBy(() -> probeContent(c)).isInstanceOf(ModelProviderException.class);
    }

    @Test void chatLengthProbeFails() throws Exception {
        HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String valid = finalJson("probe ok");
        s2.createContext("/v1/chat/completions", exchange -> {
            Map<String, Object> choice = Map.of(
                    "message", Map.of("content", valid),
                    "finish_reason", "length");
            Map<String, Object> body = Map.of("choices", List.of(choice));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        s2.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        s2.start();
        try {
            String url = "http://127.0.0.1:" + s2.getAddress().getPort() + "/v1";
            assertThatThrownBy(() -> probe.probeCustom(CustomApiFormat.CHAT_COMPLETIONS, url, null, "m"))
                    .isInstanceOf(ModelProviderException.class);
        } finally {
            s2.stop(0);
            if (s2.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
        }
    }

    @Test void responsesIncompleteProbeFails() throws Exception {
        HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String valid = finalJson("probe ok");
        s2.createContext("/v1/responses", exchange -> {
            Map<String, Object> body = Map.of(
                    "status", "incomplete",
                    "output", List.of(Map.of("type", "message",
                            "content", List.of(Map.of("type", "output_text", "text", valid)))));
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        s2.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        s2.start();
        try {
            String url = "http://127.0.0.1:" + s2.getAddress().getPort() + "/v1";
            assertThatThrownBy(() -> probe.probeCustom(CustomApiFormat.RESPONSES, url, null, "m"))
                    .isInstanceOf(ModelProviderException.class);
        } finally {
            s2.stop(0);
            if (s2.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
        }
    }
}
