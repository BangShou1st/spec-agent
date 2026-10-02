package com.specagent.model.provider;

import com.specagent.model.contract.GaModelContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GaNativeModelContractTest {
    private final GaChatCompletionsAdapter adapter = new GaChatCompletionsAdapter();

    @Test
    void sharedGoldenFixturesAcceptAndRejectIdentically() throws Exception {
        Path root = Path.of("../contracts/global-assistant/fixtures");
        try (var paths = Files.list(root)) {
            var fixtures = paths.filter(p -> p.getFileName().toString().startsWith("ga-model-")).toList();
            assertTrue(fixtures.size() >= 17, "fixture suite must not silently be empty");
            for (Path path : fixtures) {
                String json = Files.readString(path);
                Runnable read = path.getFileName().toString().contains("response")
                        ? () -> GaModelContract.readResponse(json) : () -> GaModelContract.readRequest(json);
                if (path.getFileName().toString().contains("-invalid-")) {
                    assertThrows(IllegalArgumentException.class, read::run, path.toString());
                } else {
                    assertDoesNotThrow(read::run, path.toString());
                }
            }
        }
    }

    @Test
    void nativeWirePreservesAssistantToolIdentityAndSchemaWithoutCredentials() throws Exception {
        var request = GaModelContract.readRequest(Files.readString(
                Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
        var body = adapter.requestBody(request, "pinned-model");
        assertEquals(false, body.get("stream"));
        assertEquals(false, body.get("parallel_tool_calls"));
        assertEquals("auto", body.get("tool_choice"));
        assertFalse(body.containsKey("apiKey"));
        var messages = (java.util.List<?>) body.get("messages");
        assertEquals("native-1", ((Map<?, ?>) messages.get(3)).get("tool_call_id"));
        var calls = (java.util.List<?>) ((Map<?, ?>) messages.get(2)).get("tool_calls");
        assertEquals("native-1", ((Map<?, ?>) calls.getFirst()).get("id"));
        var streamed=new GaModelContract.Request(request.protocolVersion(),request.runId(),request.executionEpoch(),request.leaseId(),
                request.callId(),request.callType(),request.modelBindingId(),request.messages(),request.tools(),request.toolChoice(),
                request.maxOutputTokens(),true);
        var streaming=adapter.streamingRequestBody(streamed,"pinned-model");
        assertEquals(true,streaming.get("stream"));
        assertEquals(body.get("messages"),streaming.get("messages"));
        assertEquals(body.get("tools"),streaming.get("tools"));
    }

    @Test
    void nativeToolCallIsParsedWithoutLeakingReasoning() {
        var response = adapter.parse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                "reasoning_content":"private must disappear","tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"project_search","arguments":"{\\"query\\":\\"test\\"}"}}]}}],
                "usage":{"prompt_tokens":10,"completion_tokens":4}}
                """);
        assertEquals("", response.content());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(Map.of("query", "test"), response.toolCalls().getFirst().arguments());
        assertFalse(response.toString().contains("private"));
    }

    @Test
    void rejectsMalformedArgumentsTruncationAndDuplicateJsonKeys() {
        for (String args : java.util.List.of("[]", "{", "{\\\"x\\\":1,\\\"x\\\":2}")) {
            String json = """
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                    "tool_calls":[{"id":"c1","type":"function","function":{"name":"project_search","arguments":"%s"}}]}}],
                    "usage":{"prompt_tokens":10,"completion_tokens":4}}
                    """.formatted(args);
            assertThrows(IllegalArgumentException.class, () -> adapter.parse(json));
        }
        String truncated = """
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"partial"}}],
                "usage":{"prompt_tokens":10,"completion_tokens":4}}
                """;
        assertThrows(IllegalArgumentException.class, () -> adapter.parse(truncated));
    }

    @Test
    void providerReservedToolsAndReusedCallIdsCannotReachTheHost() throws Exception {
        var request = GaModelContract.readRequest(Files.readString(
                Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
        for (var call : java.util.List.of(new GaModelContract.ToolCall("new", "bash", Map.of()),
                new GaModelContract.ToolCall("new", "read", Map.of()),
                new GaModelContract.ToolCall("native-1", "project_search", Map.of("query", "test")))) {
            var response = new GaModelContract.Response(GaModelContract.VERSION, "", java.util.List.of(call),
                    "tool_calls", new GaModelContract.Usage(1, 1));
            assertThrows(IllegalArgumentException.class, () -> adapter.validateResponse(request, response));
        }
    }
}
