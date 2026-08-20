package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-level tests against a local stub server. No public network is ever
 * touched: the stub records the exact request the JDK HttpClient would send
 * and answers with controlled payloads.
 */
class HttpOpenCodeZenTransportTest {

    private static final String TEST_KEY = "sk-test-only-key";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final List<CapturedRequest> captured = new ArrayList<>();
    private int stubStatus = 200;
    private String stubBody;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        stubBody = completionJson("{\"action\":\"finish\"}");
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        captured.add(new CapturedRequest(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(), requestBody));
        byte[] responseBody = stubBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                stubBody.startsWith("data:") ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(stubStatus, responseBody.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBody);
        }
    }

    private OpenCodeZenTransport transport() {
        return new HttpOpenCodeZenTransport(mapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), 5);
    }

    private OpenCodeChatCompletionRequest completionRequest() {
        return new OpenCodeChatCompletionRequest("mimo-v2.5-free",
                List.of(new OpenCodeChatMessage("system", "system contract"),
                        new OpenCodeChatMessage("user", "user context")),
                0.0, 4096);
    }

    private String completionJson(String content) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 4, "completion_tokens", 3, "total_tokens", 7)));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void completionRequestSendsOpenCodeUserAgentAndHeaders() throws IOException {
        stubBody = streamingJson("{\"action\":\"finish\"}");
        OpenCodeCompletionResponse result = transport().complete(TEST_KEY, completionRequest());

        assertThat(result.content()).isEqualTo("{\"action\":\"finish\"}");
        assertThat(captured).hasSize(1);
        CapturedRequest request = captured.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat/completions");
        assertThat(request.headers().getFirst("User-Agent")).isEqualTo(OpenCodeZenTransport.USER_AGENT);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TEST_KEY);
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");

        JsonNode payload = mapper.readTree(request.body());
        assertThat(payload.get("model").asText()).isEqualTo("mimo-v2.5-free");
        assertThat(payload.get("messages")).hasSize(2);
        assertThat(payload.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(payload.get("messages").get(1).get("content").asText()).isEqualTo("user context");
        assertThat(payload.get("temperature").asDouble()).isZero();
        assertThat(payload.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(payload.get("response_format")).isNull();
        assertThat(payload.get("stream").asBoolean()).isTrue();
    }

    @Test
    void streamingChunksAreAggregatedUntilDone() {
        stubBody = streamingJson("foo", "bar");

        OpenCodeCompletionResponse result = transport().complete(TEST_KEY, completionRequest());

        assertThat(result.content()).isEqualTo("foobar");
    }

    @Test
    void modelListRequestSendsOpenCodeUserAgentAndAuth() throws IOException {
        stubBody = mapper.writeValueAsString(Map.of("object", "list", "data", List.of(
                Map.of("id", "paid-model", "object", "model"),
                Map.of("id", "one-free", "object", "model"))));

        OpenCodeModelList models = transport().listModels(TEST_KEY);

        assertThat(models.data()).extracting(OpenCodeModel::id)
                .containsExactly("paid-model", "one-free");
        CapturedRequest request = captured.get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/models");
        assertThat(request.headers().getFirst("User-Agent")).isEqualTo(OpenCodeZenTransport.USER_AGENT);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TEST_KEY);
        assertThat(request.headers().getFirst("Content-Type")).isNull();
    }

    @Test
    void modelListRequestWorksWithoutAuthorization() throws IOException {
        stubBody = mapper.writeValueAsString(Map.of("data", List.of(
                Map.of("id", "one-free", "object", "model"))));

        OpenCodeModelList models = transport().listModels(null);

        assertThat(models.data()).extracting(OpenCodeModel::id).containsExactly("one-free");
        assertThat(captured.get(0).headers().getFirst("Authorization")).isNull();
        assertThat(captured.get(0).headers().getFirst("User-Agent"))
                .isEqualTo(OpenCodeZenTransport.USER_AGENT);
    }

    @Test
    void credentialProbeUsesSameOpenCodeTransport() throws IOException {
        transport().validateCredential(TEST_KEY, "current-free");

        CapturedRequest request = captured.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat/completions");
        // Probe requests must carry exactly the same transport policy.
        assertThat(request.headers().getFirst("User-Agent")).isEqualTo(OpenCodeZenTransport.USER_AGENT);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TEST_KEY);
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");

        // The probe model comes from the caller (currently discovered free
        // model); the transport never hardcodes one.
        JsonNode payload = mapper.readTree(request.body());
        assertThat(payload.get("model").asText()).isEqualTo("current-free");
        assertThat(payload.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(payload.get("response_format").get("type").asText()).isEqualTo("json_object");
        assertThat(payload.get("stream").asBoolean()).isFalse();
    }

    @Test
    void authenticationFailureIsMapped() {
        stubStatus = 401;
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.AUTHENTICATION);
                    assertThat(modelException.httpStatus()).isEqualTo(401);
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(401);
                });

        stubStatus = 403;
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.AUTHENTICATION));
    }

    @Test
    void rateLimitIsMapped() {
        stubStatus = 429;
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.RATE_LIMITED);
                    assertThat(modelException.httpStatus()).isEqualTo(429);
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(429);
                    assertThat(captured).hasSize(1);
                });
    }

    @Test
    void serverErrorIsMapped() {
        stubStatus = 504;
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.SERVER_ERROR);
                    assertThat(modelException.httpStatus()).isEqualTo(504);
                });
    }

    @Test
    void otherClientErrorIsMapped() {
        stubStatus = 400;
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR);
                    assertThat(modelException.httpStatus()).isEqualTo(400);
                });
    }

    @Test
    void malformedJsonIsMappedToInvalidResponse() {
        stubBody = "data: not-json-at-all\n\n";
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.STREAM_MALFORMED_JSON);
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(200);
                    assertThat(modelException.diagnostics().eventIndex()).isEqualTo(1);
                });
    }

    @Test
    void unexpectedCompletionPayloadIsMappedToInvalidResponse() throws IOException {
        stubBody = "data: " + mapper.writeValueAsString(Map.of("object", "list", "data", List.of())) + "\n\n";
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.STREAM_MISSING_CHOICES);
                    assertThat(modelException.diagnostics().topLevelFields())
                            .containsExactly("object", "data");
                });
    }

    @Test
    void providerErrorEventAtHttp200IsMappedWithoutGenericMalformedChunk() throws IOException {
        stubBody = streamingError("provider_error", "upstream_failure", "provider is temporarily busy");

        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category())
                            .isEqualTo(OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR);
                    assertThat(modelException.httpStatus()).isEqualTo(200);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.STREAM_ERROR_EVENT);
                    assertThat(modelException.diagnostics().providerType()).isEqualTo("provider_error");
                    assertThat(modelException.diagnostics().providerCode()).isEqualTo("upstream_failure");
                    assertThat(modelException.diagnostics().providerMessage())
                            .isEqualTo("provider is temporarily busy");
                });
    }

    @Test
    void knownRateLimitProviderErrorEventAtHttp200MapsToRateLimited() throws IOException {
        stubBody = streamingError("rate_limit", "too_many_requests", "slow down");

        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.RATE_LIMITED);
                    assertThat(modelException.diagnostics().diagnosticReason())
                            .isEqualTo(OpenCodeDiagnosticReason.STREAM_ERROR_EVENT);
                    assertThat(modelException.diagnostics().providerCode()).isEqualTo("too_many_requests");
                });
    }

    @Test
    void roleOnlyAndUsageOnlyChunksAreAccepted() {
        stubBody = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        OpenCodeCompletionResponse result = transport().complete(TEST_KEY, completionRequest());

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.promptTokens()).isEqualTo(2);
        assertThat(result.completionTokens()).isEqualTo(1);
        assertThat(result.totalTokens()).isEqualTo(3);
        assertThat(result.streamedEventCount()).isEqualTo(4);
    }

    @Test
    void missingDeltaIsDiagnosedPrecisely() throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("finish_reason", null);
        stubBody = streamEvent(mapper.writeValueAsString(Map.of("choices", List.of(choice))));

        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).diagnostics().diagnosticReason())
                        .isEqualTo(OpenCodeDiagnosticReason.STREAM_MISSING_DELTA));
    }

    @Test
    void nonTextDeltaContentIsDiagnosedPrecisely() throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", Map.of("content", List.of("not-text")));
        choice.put("finish_reason", null);
        stubBody = streamEvent(mapper.writeValueAsString(Map.of("choices", List.of(choice))));

        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).diagnostics().diagnosticReason())
                        .isEqualTo(OpenCodeDiagnosticReason.STREAM_NON_TEXT_CONTENT));
    }

    @Test
    void non2xxIncludingRedirectIsRejectedBeforeSseParsing() {
        stubStatus = 302;
        stubBody = streamingJson("would-not-be-parsed");

        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    assertThat(modelException.category())
                            .isEqualTo(OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR);
                    assertThat(modelException.httpStatus()).isEqualTo(302);
                    assertThat(modelException.diagnostics().initialHttpStatus()).isEqualTo(302);
                    assertThat(modelException.diagnostics().diagnosticReason()).isNull();
                });
    }

    @Test
    void streamDiagnosticsContainMetadataButNotRawBodyOrCredential() throws IOException {
        String rawBodyMarker = "raw-body-marker-that-must-not-escape";
        stubBody = "data: " + mapper.writeValueAsString(Map.of(
                "error", Map.of("type", "provider_error", "code", "bad_response",
                        "message", "Bearer sk-provider-secret-value"),
                "raw_body", rawBodyMarker)) + "\n\n";

        assertThatThrownBy(() -> transport().complete("sk-test-credential", completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> {
                    OpenCodeModelException modelException = (OpenCodeModelException) ex;
                    String diagnosticText = modelException.diagnostics().toString();
                    assertThat(diagnosticText).doesNotContain("sk-provider-secret-value");
                    assertThat(diagnosticText).doesNotContain(rawBodyMarker);
                    assertThat(diagnosticText).doesNotContain("Authorization");
                    assertThat(modelException.diagnostics().providerMessage())
                            .isEqualTo("Bearer <redacted>");
                });
    }

    @Test
    void emptyModelContentIsMappedToEmptyContent() {
        stubBody = streamingJson("   ");
        assertThatThrownBy(() -> transport().complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.EMPTY_CONTENT));
    }

    @Test
    void unexpectedModelListPayloadIsMappedToInvalidResponse() throws IOException {
        stubBody = mapper.writeValueAsString(Map.of("data", "not-an-array"));
        assertThatThrownBy(() -> transport().listModels(TEST_KEY))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.INVALID_RESPONSE));
    }

    @Test
    void timeoutIsMappedToTimeoutCategory() throws Exception {
        try (ServerSocket blackhole = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                while (true) {
                    try {
                        blackhole.accept();
                    } catch (IOException ex) {
                        break;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            OpenCodeZenTransport transport = new HttpOpenCodeZenTransport(mapper,
                    "http://127.0.0.1:" + blackhole.getLocalPort(), 1);
            assertThatThrownBy(() -> transport.complete(TEST_KEY, completionRequest()))
                    .isInstanceOf(OpenCodeModelException.class)
                    .satisfies(ex -> {
                        OpenCodeModelException modelException = (OpenCodeModelException) ex;
                        assertThat(modelException.category()).isEqualTo(OpenCodeModelErrorCategory.TIMEOUT);
                        assertThat(modelException.getMessage()).contains("timed out");
                    });
        }
    }

    @Test
    void connectionFailureIsMappedToConnectionCategory() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        OpenCodeZenTransport transport = new HttpOpenCodeZenTransport(mapper,
                "http://127.0.0.1:" + freePort, 5);

        assertThatThrownBy(() -> transport.complete(TEST_KEY, completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(((OpenCodeModelException) ex).category())
                        .isEqualTo(OpenCodeModelErrorCategory.CONNECTION));
    }

    @Test
    void errorsNeverContainApiKey() {
        stubStatus = 401;
        assertThatThrownBy(() -> transport().complete("sk-super-secret-value", completionRequest()))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("sk-super-secret-value"));
    }

    private record CapturedRequest(String method, String path, Headers headers, byte[] body) {
    }

    private String streamingJson(String... contents) {
        StringBuilder stream = new StringBuilder();
        for (String content : contents) {
            try {
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("index", 0);
                choice.put("delta", Map.of("content", content));
                choice.put("finish_reason", null);
                stream.append("data: ")
                        .append(mapper.writeValueAsString(Map.of("choices", List.of(choice))))
                        .append("\n\n");
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return stream.append("data: [DONE]\n\n").toString();
    }

    private String streamingError(String type, String code, String message) throws IOException {
        return streamEvent(mapper.writeValueAsString(Map.of(
                "error", Map.of("type", type, "code", code, "message", message))));
    }

    private String streamEvent(String json) {
        return "data: " + json + "\n\n";
    }
}
