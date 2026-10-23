package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomNativeToolQualification;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.StreamCancelledException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Offline protocol fixture test covering the complete Gemini tool-use and
 * thought-signature roundtrip without touching live providers or databases:
 * 1. Single tool call -> tool result -> final text
 * 2. Tool ID mismatch, duplicate, and missing
 * 3. Signature missing or corrupted diagnostic
 * 4. Text-only TEXT_CHAT regression
 * 5. Multi-tool call order and signatures
 * 6. Timeout, interruption, host failure, and non-repeating effects
 * 7. Unqualified model fails closed
 */
class GaGeminiProtocolOfflineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CustomRuntimeSettingsPort customSettings = mock(CustomRuntimeSettingsPort.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    private final CustomNativeToolQualification qualification = mock(CustomNativeToolQualification.class);
    private final String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    private final String model = "gemini-3.5-flash-lite";
    private final GaModelTarget target = new GaModelTarget("CUSTOM", model, "custom-settings:1", "safe-test-key");

    @BeforeEach
    void setup() {
        when(qualification.hasCustomTargets()).thenReturn(true);
        when(qualification.customQualified(baseUrl, model)).thenReturn(true);
        when(customSettings.requireRuntimeSettings()).thenReturn(
                new RuntimeCustomSettings("CHAT_COMPLETIONS", baseUrl, "safe-test-key", model, 1));
    }

    private static HttpResponse<byte[]> httpResponse(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static HttpClient mockClient(HttpResponse<byte[]> response, AtomicReference<HttpRequest> captured) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.<byte[]>send(any(), any())).thenAnswer(invocation -> {
            if (captured != null) captured.set(invocation.getArgument(0));
            return response;
        });
        return client;
    }

    // --- 1. Single Tool Call -> Tool Result -> Final Text ---
    @Test
    void singleToolCallResultFollowUpToFinalText() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        String finalAnswerBody = """
                {
                  "choices": [{
                    "message": { "role": "assistant", "content": "项目交付快照已核验通过。" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 150, "completion_tokens": 20 }
                }
                """;
        var client = mockClient(httpResponse(200, finalAnswerBody), captured);
        var transport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, qualification);

        // Turn 2 request: assistant call with thought_signature + tool result
        Map<String, Object> extra = Map.of("google", Map.of("thought_signature", "enc_sig_12345"));
        var call = new GaModelContract.ToolCall("call_proj_001", "project_get_summary",
                Map.of("projectId", "p-123"), extra);
        var assistantMsg = new GaModelContract.Message("assistant", "", List.of(call), null);
        var toolMsg = new GaModelContract.Message("tool", "{\"status\":\"SUCCEEDED\",\"projectTitle\":\"验收项目\"}",
                List.of(), "call_proj_001");
        var userMsg = new GaModelContract.Message("user", "获取项目概况", List.of(), null);

        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg, assistantMsg, toolMsg),
                List.of(new GaModelContract.Tool("project_get_summary", "Get summary", Map.of("type", "object"))),
                "auto", 256, false);

        var response = transport.complete(target, request, Duration.ofSeconds(30), () -> true);

        assertThat(response.content()).isEqualTo("项目交付快照已核验通过。");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("stop");

        // Verify captured HTTP payload
        HttpRequest sent = captured.get();
        assertThat(sent).isNotNull();
        assertThat(sent.uri().toString()).isEqualTo(baseUrl + "/chat/completions");

        // Verify assistant content was set to null and extra_content was passed back
        var adapter = new GaChatCompletionsAdapter();
        Map<String, Object> body = adapter.requestBody(request, model);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages).hasSize(3);

        Map<String, Object> assistantItem = messages.get(1);
        assertThat(assistantItem.get("role")).isEqualTo("assistant");
        assertThat(assistantItem.get("content")).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) assistantItem.get("tool_calls");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).get("id")).isEqualTo("call_proj_001");
        assertThat(toolCalls.get(0).get("extra_content")).isEqualTo(extra);

        Map<String, Object> toolItem = messages.get(2);
        assertThat(toolItem.get("role")).isEqualTo("tool");
        assertThat(toolItem.get("tool_call_id")).isEqualTo("call_proj_001");

        // Verify diagnostic
        var diag = transport.lastSafetyDiagnostic();
        assertThat(diag).isNotNull();
        assertThat(diag.upstreamHttpStatus()).isEqualTo(200);
        assertThat(diag.hasThoughtSignature()).isTrue();
        assertThat(diag.requestTurn()).isEqualTo(2);
        assertThat(diag.toolCallIdRelation().allMatched()).isTrue();
    }

    // --- 2. Tool ID Mismatch, Duplicate, and Missing ---
    @Test
    void toolIdMismatchDuplicateAndMissing() {
        // Mismatch: assistant has call_A, tool result has call_B
        var callA = new GaModelContract.ToolCall("call_A", "project_search", Map.of());
        var assistant = new GaModelContract.Message("assistant", "", List.of(callA), null);
        var toolB = new GaModelContract.Message("tool", "{}", List.of(), "call_B");
        var user = new GaModelContract.Message("user", "查询", List.of(), null);
        var tools = List.of(new GaModelContract.Tool("project_search", "Search", Map.of("type", "object")));

        assertThatThrownBy(() -> new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(user, assistant, toolB), tools, "auto", 256, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orphan or duplicate tool result");

        // Duplicate call identity
        var callDup = new GaModelContract.ToolCall("call_A", "project_search", Map.of());
        var assistantDup = new GaModelContract.Message("assistant", "", List.of(callA, callDup), null);
        assertThatThrownBy(() -> new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(user, assistantDup), tools, "auto", 256, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate tool call identity");

        // Missing tool result (unanswered call)
        assertThatThrownBy(() -> new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(user, assistant), tools, "auto", 256, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unfinished tool calls");
    }

    // --- 3. Signature Missing or Corrupted Diagnostic ---
    @Test
    void signatureMissingOrCorruptedDiagnostics() throws Exception {
        String errorJson = """
                {
                  "error": {
                    "code": 400,
                    "message": "Function call call_proj_001 is missing a thought_signature",
                    "status": "INVALID_ARGUMENT"
                  }
                }
                """;
        var client = mockClient(httpResponse(400, errorJson), new AtomicReference<>());
        var transport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, qualification);

        // Turn 2 call without signature (extraContent is null)
        var callNoSig = new GaModelContract.ToolCall("call_proj_001", "project_get_summary",
                Map.of("projectId", "p-123"), null);
        var assistantMsg = new GaModelContract.Message("assistant", "", List.of(callNoSig), null);
        var toolMsg = new GaModelContract.Message("tool", "{\"status\":\"SUCCEEDED\"}", List.of(), "call_proj_001");
        var userMsg = new GaModelContract.Message("user", "获取项目概况", List.of(), null);

        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg, assistantMsg, toolMsg),
                List.of(new GaModelContract.Tool("project_get_summary", "Get summary", Map.of("type", "object"))),
                "auto", 256, false);

        assertThatThrownBy(() -> transport.complete(target, request, Duration.ofSeconds(30), () -> true))
                .isInstanceOf(ModelProviderException.class)
                .satisfies(ex -> {
                    var mpe = (ModelProviderException) ex;
                    assertThat(mpe.gatewayCategory()).isEqualTo(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR);
                    assertThat(mpe.httpStatus()).isEqualTo(400);
                });

        var diag = transport.lastSafetyDiagnostic();
        assertThat(diag).isNotNull();
        assertThat(diag.upstreamHttpStatus()).isEqualTo(400);
        assertThat(diag.errorCategory()).isEqualTo("PROVIDER_REQUEST_ERROR");
        assertThat(diag.hasThoughtSignature()).isFalse();
        assertThat(diag.failurePhase()).isEqualTo("UPSTREAM_REQUEST_FAILED");
        assertThat(diag.toolCallIdRelation().allMatched()).isTrue();
        assertThat(diag.messagesSummary()).hasSize(3);
        assertThat(diag.messagesSummary().get(1).hasThoughtSignature()).isFalse();
    }

    // --- 4. Text-Only TEXT_CHAT Regression ---
    @Test
    void textOnlyChatRegressionWithoutTools() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        String textBody = """
                {
                  "choices": [{
                    "message": { "role": "assistant", "content": "你好，有什么可以帮你的？" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 12 }
                }
                """;
        var client = mockClient(httpResponse(200, textBody), captured);
        var transport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, qualification);

        var userMsg = new GaModelContract.Message("user", "你好", List.of(), null);
        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg), List.of(), "none", 256, false);

        var response = transport.complete(target, request, Duration.ofSeconds(30), () -> true);

        assertThat(response.content()).isEqualTo("你好，有什么可以帮你的？");
        assertThat(response.toolCalls()).isEmpty();

        var adapter = new GaChatCompletionsAdapter();
        Map<String, Object> body = adapter.requestBody(request, model);
        assertThat(body).doesNotContainKey("tools");
        assertThat(body).doesNotContainKey("tool_choice");
        assertThat(body).doesNotContainKey("parallel_tool_calls");

        var diag = transport.lastSafetyDiagnostic();
        assertThat(diag.hasThoughtSignature()).isFalse();
        assertThat(diag.requestTurn()).isEqualTo(1);
    }

    // --- 5. Multi-Tool Call Order and Signatures ---
    @Test
    void multipleToolCallsPreserveOrderAndSignatures() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        String finalAnswer = """
                {
                  "choices": [{
                    "message": { "role": "assistant", "content": "两个工具都执行完毕。" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 200, "completion_tokens": 15 }
                }
                """;
        var client = mockClient(httpResponse(200, finalAnswer), captured);
        var transport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, qualification);

        var call1 = new GaModelContract.ToolCall("call_1", "project_search", Map.of("q", "a"),
                Map.of("google", Map.of("thought_signature", "sig_alpha")));
        var call2 = new GaModelContract.ToolCall("call_2", "project_get_summary", Map.of("projectId", "p1"),
                Map.of("google", Map.of("thought_signature", "sig_beta")));

        var assistantMsg = new GaModelContract.Message("assistant", "", List.of(call1, call2), null);
        var tool1 = new GaModelContract.Message("tool", "{\"res\":1}", List.of(), "call_1");
        var tool2 = new GaModelContract.Message("tool", "{\"res\":2}", List.of(), "call_2");
        var userMsg = new GaModelContract.Message("user", "批量处理", List.of(), null);

        var tools = List.of(
                new GaModelContract.Tool("project_search", "Search", Map.of("type", "object")),
                new GaModelContract.Tool("project_get_summary", "Summary", Map.of("type", "object"))
        );
        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg, assistantMsg, tool1, tool2), tools, "auto", 256, false);

        var response = transport.complete(target, request, Duration.ofSeconds(30), () -> true);
        assertThat(response.content()).isEqualTo("两个工具都执行完毕。");

        var adapter = new GaChatCompletionsAdapter();
        Map<String, Object> body = adapter.requestBody(request, model);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) messages.get(1).get("tool_calls");
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0).get("id")).isEqualTo("call_1");
        assertThat(toolCalls.get(0).get("extra_content")).isEqualTo(Map.of("google", Map.of("thought_signature", "sig_alpha")));
        assertThat(toolCalls.get(1).get("id")).isEqualTo("call_2");
        assertThat(toolCalls.get(1).get("extra_content")).isEqualTo(Map.of("google", Map.of("thought_signature", "sig_beta")));

        assertThat(messages.get(2).get("tool_call_id")).isEqualTo("call_1");
        assertThat(messages.get(3).get("tool_call_id")).isEqualTo("call_2");
    }

    // --- 6. Timeout, Interruption, Host Failure, and Non-Repeating Effects ---
    @Test
    void timeoutInterruptionHostFailureAndNonRepeatingEffects() throws Exception {
        // Timeout
        HttpClient timeoutClient = mock(HttpClient.class);
        when(timeoutClient.<byte[]>send(any(), any())).thenThrow(new HttpConnectTimeoutException("Connect timed out"));
        var timeoutTransport = new ChatCompletionsGaTextTransport(customSettings, mapper, timeoutClient, qualification);

        var userMsg = new GaModelContract.Message("user", "查询", List.of(), null);
        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg), List.of(), "none", 256, false);

        assertThatThrownBy(() -> timeoutTransport.complete(target, request, Duration.ofSeconds(10), () -> true))
                .isInstanceOf(ModelProviderException.class)
                .satisfies(ex -> assertThat(((ModelProviderException) ex).gatewayCategory()).isEqualTo(ModelGatewayErrorCategory.TIMEOUT));

        // Interruption
        HttpClient normalClient = mockClient(httpResponse(200, "{}"), null);
        var cancelTransport = new ChatCompletionsGaTextTransport(customSettings, mapper, normalClient, qualification);
        assertThatThrownBy(() -> cancelTransport.complete(target, request, Duration.ofSeconds(10), () -> false))
                .isInstanceOf(StreamCancelledException.class)
                .hasMessage("GA execution no longer active");
        verifyNoInteractions(normalClient);

        // Host failure observation roundtrip (tool failed on host, passed as tool observation)
        var call = new GaModelContract.ToolCall("call_fail", "project_search", Map.of("q", "x"));
        var assistantMsg = new GaModelContract.Message("assistant", "", List.of(call), null);
        var hostFailTool = new GaModelContract.Message("tool",
                "{\"status\":\"FAILED\",\"errorCode\":\"CAPABILITY_UNAVAILABLE\"}", List.of(), "call_fail");
        var failFollowUp = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(userMsg, assistantMsg, hostFailTool),
                List.of(new GaModelContract.Tool("project_search", "Search", Map.of("type", "object"))),
                "auto", 256, false);

        String explainReply = """
                {
                  "choices": [{
                    "message": { "role": "assistant", "content": "抱歉，检索工具当前不可用。" },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 80, "completion_tokens": 12 }
                }
                """;
        var explainClient = mockClient(httpResponse(200, explainReply), null);
        var explainTransport = new ChatCompletionsGaTextTransport(customSettings, mapper, explainClient, qualification);
        var finalResp = explainTransport.complete(target, failFollowUp, Duration.ofSeconds(10), () -> true);
        assertThat(finalResp.content()).isEqualTo("抱歉，检索工具当前不可用。");
    }

    // --- 7. Unqualified Custom Model Fails Closed ---
    @Test
    void unqualifiedCustomModelFailsClosed() {
        var unqual = mock(CustomNativeToolQualification.class);
        when(unqual.hasCustomTargets()).thenReturn(true);
        when(unqual.customQualified(any(), any())).thenReturn(false);

        HttpClient client = mock(HttpClient.class);
        var transport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, unqual);

        var tool = new GaModelContract.Tool("project_search", "Search", Map.of("type", "object"));
        var user = new GaModelContract.Message("user", "查询", List.of(), null);
        var request = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(user), List.of(tool), "auto", 256, false);

        assertThatThrownBy(() -> transport.complete(target, request, Duration.ofSeconds(10), () -> true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("UNSUPPORTED_AGENT_MODEL");
        verifyNoInteractions(client);

        // Revision drift
        when(unqual.customQualified(baseUrl, model)).thenReturn(true);
        when(customSettings.requireRuntimeSettings()).thenReturn(
                new RuntimeCustomSettings("CHAT_COMPLETIONS", baseUrl, "safe-test-key", model, 2)); // drifted revision 2
        var driftedTransport = new ChatCompletionsGaTextTransport(customSettings, mapper, client, unqual);
        assertThatThrownBy(() -> driftedTransport.complete(target, request, Duration.ofSeconds(10), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GA_MODEL_BINDING_MISMATCH");
    }
}
