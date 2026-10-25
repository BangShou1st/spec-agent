package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.support.FakeAnthropicMessagesProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MS-C3 protocol contract for the Custom Anthropic Messages GA transport:
 * request shape, tool_use identity, fail-closed parsing, strict fake
 * assertions and frozen binding semantics.
 */
class AnthropicGaNativeTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static RuntimeCustomSettings settings(String baseUrl, long revision, UUID id, boolean nativeTools) {
        return new RuntimeCustomSettings("ANTHROPIC_MESSAGES", baseUrl, "fixture-secret",
                "anthropic-model", revision, id, nativeTools);
    }

    private static CustomRuntimeSettingsPort port(RuntimeCustomSettings settings) {
        return new CustomRuntimeSettingsPort() {
            @Override
            public RuntimeCustomSettings requireRuntimeSettings() {
                return settings;
            }

            @Override
            public RuntimeCustomSettings requireRuntimeSettings(String frozenRevision) {
                if (!settings.bindingRevision().equals(frozenRevision)) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                return settings;
            }
        };
    }

    private static AnthropicGaNativeTransport transport(RuntimeCustomSettings settings, ObjectMapper mapper) {
        return new AnthropicGaNativeTransport(port(settings), mapper,
                java.net.http.HttpClient.newHttpClient());
    }

    private static GaModelTarget target(RuntimeCustomSettings settings) {
        return new GaModelTarget("CUSTOM", "anthropic-model",
                settings.bindingRevision(), "fixture-secret");
    }

    private static GaModelContract.Request textRequest() {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("system", "be brief", List.of(), null),
                        new GaModelContract.Message("user", "你好", List.of(), null)),
                List.of(), "none", 256, false);
    }

    private static GaModelContract.Request toolRequest(String choice) {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "probe", List.of(), null)),
                List.of(new GaModelContract.Tool("probe_echo", "echo",
                        Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text"), "additionalProperties", false))),
                choice, 256, false);
    }

    @Test
    void chatUsesTopLevelSystemAndAnthropicIdentityHeaders() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var response = transport(runtime, mapper).complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true);

            assertThat(response.content()).isEqualTo("anthropic final answer");
            assertThat(fake.requests).hasSize(1);
            JsonNode body = fake.requests.getFirst();
            assertThat(body.path("model").asText()).isEqualTo("anthropic-model");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(body.path("system").asText()).isEqualTo("be brief");
            assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(body.has("tools")).isFalse();
            assertThat(body.has("tool_choice")).isFalse();
            assertThat(body.has("input")).isFalse();
            assertThat(body.has("response_format")).isFalse();
            assertThat(body.path("stream").isMissingNode()).isTrue();
            assertThat(fake.apiKeys).containsOnly("fixture-secret");
            assertThat(fake.versions).containsOnly(AnthropicMessagesProtocolAdapter.ANTHROPIC_VERSION);
        }
    }

    @Test
    void toolUseRoundTripPreservesAnthropicToolUseId() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.SINGLE;
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            var first = transport.complete(target(runtime), toolRequest("required"),
                    Duration.ofSeconds(30), () -> true);

            assertThat(first.finishReason()).isEqualTo("tool_calls");
            assertThat(first.toolCalls()).hasSize(1);
            assertThat(first.toolCalls().getFirst().name()).isEqualTo("probe_echo");
            assertThat(first.toolCalls().getFirst().arguments()).isEqualTo(Map.of("text", "probe ok"));
            String callId = first.toolCalls().getFirst().id();
            assertThat(callId).isEqualTo("toolu_01");

            var assistant = new GaModelContract.Message("assistant", "", List.of(
                    new GaModelContract.ToolCall(callId, "probe_echo", Map.of("text", "probe ok"))), null);
            var tool = new GaModelContract.Message("tool", "{\"status\":\"SUCCEEDED\"}", List.of(), callId);
            var followUp = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1,
                    UUID.randomUUID(), UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                    List.of(new GaModelContract.Message("user", "probe", List.of(), null), assistant, tool),
                    List.of(new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"))),
                    "none", 256, false);
            var second = transport.complete(target(runtime), followUp, Duration.ofSeconds(30), () -> true);

            assertThat(second.content()).isEqualTo("anthropic final answer");
            assertThat(second.toolCalls()).isEmpty();
            JsonNode followUpBody = fake.requests.get(1);
            JsonNode assistantHistory = followUpBody.path("messages").get(1);
            assertThat(assistantHistory.path("role").asText()).isEqualTo("assistant");
            JsonNode historyBlock = assistantHistory.path("content").get(0);
            assertThat(historyBlock.path("type").asText()).isEqualTo("tool_use");
            assertThat(historyBlock.path("id").asText()).isEqualTo(callId);
            assertThat(historyBlock.path("name").asText()).isEqualTo("probe_echo");
            assertThat(historyBlock.path("input").path("text").asText()).isEqualTo("probe ok");
            assertThat(assistantHistory.has("tool_calls")).isFalse();
            JsonNode userResult = followUpBody.path("messages").get(2);
            assertThat(userResult.path("role").asText()).isEqualTo("user");
            JsonNode resultBlock = userResult.path("content").get(0);
            assertThat(resultBlock.path("type").asText()).isEqualTo("tool_result");
            assertThat(resultBlock.path("tool_use_id").asText()).isEqualTo(callId);
            assertThat(resultBlock.path("content").asText()).isEqualTo("{\"status\":\"SUCCEEDED\"}");
            assertThat(followUpBody.path("tool_choice").path("type").asText()).isEqualTo("none");
        }
    }

    @Test
    void toolChoiceMapsNoneAutoAndRequiredToAnthropicTypes() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.SINGLE;
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            transport.complete(target(runtime), toolRequest("required"), Duration.ofSeconds(30), () -> true);
            assertThat(fake.requests.getFirst().path("tool_choice").path("type").asText()).isEqualTo("any");
            assertThat(fake.requests.getFirst().path("tool_choice").path("disable_parallel_tool_use")
                    .asBoolean()).isTrue();
            JsonNode toolDefinition = fake.requests.getFirst().path("tools").get(0);
            assertThat(toolDefinition.path("name").asText()).isEqualTo("probe_echo");
            assertThat(toolDefinition.path("input_schema").path("type").asText()).isEqualTo("object");
            assertThat(toolDefinition.has("parameters")).isFalse();
            assertThat(toolDefinition.has("function")).isFalse();
        }
    }

    @Test
    void legalTextThenToolUseIsSupportedAndRoundTrips() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.TEXT_THEN_TOOL;
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            var first = transport.complete(target(runtime), toolRequest("auto"),
                    Duration.ofSeconds(30), () -> true);

            assertThat(first.finishReason()).isEqualTo("tool_calls");
            assertThat(first.content()).isEqualTo("I will search first.");
            assertThat(first.toolCalls()).hasSize(1);
            String callId = first.toolCalls().getFirst().id();

            var assistant = new GaModelContract.Message("assistant", first.content(),
                    List.of(first.toolCalls().getFirst()), null);
            var tool = new GaModelContract.Message("tool", "{\"ok\":true}", List.of(), callId);
            var followUp = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1,
                    UUID.randomUUID(), UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                    List.of(new GaModelContract.Message("user", "probe", List.of(), null), assistant, tool),
                    List.of(new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"))),
                    "none", 256, false);
            var second = transport.complete(target(runtime), followUp, Duration.ofSeconds(30), () -> true);
            assertThat(second.content()).isEqualTo("anthropic final answer");

            // 历史必须按 text block → tool_use block → user tool_result 精确恢复。
            JsonNode assistantHistory = fake.requests.get(1).path("messages").get(1);
            assertThat(assistantHistory.path("content").get(0).path("type").asText()).isEqualTo("text");
            assertThat(assistantHistory.path("content").get(1).path("type").asText()).isEqualTo("tool_use");
            JsonNode userResult = fake.requests.get(1).path("messages").get(2);
            assertThat(userResult.path("role").asText()).isEqualTo("user");
            assertThat(userResult.path("content").get(0).path("type").asText()).isEqualTo("tool_result");
            assertThat(fake.rejections).isEmpty();
        }
    }

    @Test
    void toolUseFollowedByTextIsRejectedBeforeRelease() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.TOOL_THEN_TEXT;
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            assertThatThrownBy(() -> transport(runtime, mapper).complete(target(runtime),
                    toolRequest("required"), Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("text after tool_use");
            // 只发生了第一次 Provider 请求,工具从未释放。
            assertThat(fake.requests).hasSize(1);
        }
    }

    @Test
    void thinkingPlusToolUseIsRejectedBeforeRelease() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.THINKING_THEN_TOOL;
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            assertThatThrownBy(() -> transport(runtime, mapper).complete(target(runtime),
                    toolRequest("required"), Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("thinking blocks cannot accompany tool_use");
            assertThat(fake.requests).hasSize(1);
        }
    }

    @Test
    void duplicateToolUseIdAndStopReasonMismatchFailClosed() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.DUPLICATE_ID;
            assertThatThrownBy(() -> transport.complete(target(runtime), toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.STOP_REASON_MISMATCH;
            assertThatThrownBy(() -> transport.complete(target(runtime), toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("completed stop_reason with tool blocks");
        }
    }

    @Test
    void refusalMaxTokensPauseTurnAndNullStopReasonNeverSucceed() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            fake.refusalStop = true;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalArgumentException.class);
            fake.refusalStop = false;
            fake.pauseTurnStop = true;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.pauseTurnStop = false;
            fake.maxTokensStop = true;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.maxTokensStop = false;
            fake.nullStop = true;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void twoReadOnlyToolCallsMergeResultsIntoOneUserMessage() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            fake.toolShape = FakeAnthropicMessagesProvider.ToolShape.MULTI;
            fake.secondToolName = "second_echo";
            fake.secondToolArguments = "{\"text\":\"second\"}";
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            var tool = new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"));
            var secondTool = new GaModelContract.Tool("second_echo", "echo twice",
                    Map.of("type", "object"));
            var user = new GaModelContract.Message("user", "probe twice", List.of(), null);
            var first = transport.complete(target(runtime), new GaModelContract.Request(
                    GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                    UUID.randomUUID(), "AGENT", UUID.randomUUID(), List.of(user),
                    List.of(tool, secondTool), "required", 256, false),
                    Duration.ofSeconds(30), () -> true);

            assertThat(first.toolCalls()).hasSize(2);
            var firstCall = first.toolCalls().get(0);
            var secondCall = first.toolCalls().get(1);
            assertThat(firstCall.id()).isNotEqualTo(secondCall.id());

            var assistant = new GaModelContract.Message("assistant", "",
                    List.of(firstCall, secondCall), null);
            var firstResult = new GaModelContract.Message("tool", "{\"ok\":1}", List.of(), firstCall.id());
            var secondResult = new GaModelContract.Message("tool", "{\"ok\":2}", List.of(), secondCall.id());
            var followUp = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1,
                    UUID.randomUUID(), UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                    List.of(user, assistant, firstResult, secondResult),
                    List.of(tool, secondTool), "none", 256, false);
            var second = transport.complete(target(runtime), followUp, Duration.ofSeconds(30), () -> true);
            assertThat(second.content()).isEqualTo("anthropic final answer");

            // 两个 tool_result 必须在同一条 user 消息的两个 block 中,顺序保留。
            JsonNode messages = fake.requests.get(1).path("messages");
            assertThat(messages.size()).isEqualTo(3);
            JsonNode userResults = messages.get(2);
            assertThat(userResults.path("role").asText()).isEqualTo("user");
            assertThat(userResults.path("content").size()).isEqualTo(2);
            assertThat(userResults.path("content").get(0).path("tool_use_id").asText())
                    .isEqualTo(firstCall.id());
            assertThat(userResults.path("content").get(1).path("tool_use_id").asText())
                    .isEqualTo(secondCall.id());
            assertThat(fake.rejections).isEmpty();
        }
    }

    @Test
    void strictFakeRejectsForeignProtocolShapesAndIllegalRequests() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"probe",
                    "tool_calls":[{"id":"a","function":{"name":"probe_echo","arguments":"{}"}}]}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("tool_calls is chat-completions shape");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"probe"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"a","name":"probe_echo","input":{}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"ghost","content":"{}"}]}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("orphan tool_result");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"probe"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"a","name":"probe_echo","input":{}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"{}"}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"{}"}]}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("duplicate tool_result");

            post(fake, """
                    {"model":"m","messages":[{"role":"user","content":"no max tokens"}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("max_tokens is required");

            post(fake, """
                    {"model":"m","max_tokens":16,"tool_choice":"auto","messages":[{"role":"user","content":"x"}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("tool_choice must be an object");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"x"}],
                    "tools":[{"name":"probe_echo","description":"d","parameters":{"type":"object"}}]}
                    """);
            assertThat(fake.rejections.getLast()).contains("chat completions tool shape is forbidden");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"x"}],"response_format":{"type":"json_object"}}
                    """);
            assertThat(fake.rejections.getLast()).contains("response_format is forbidden");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"x"}],"output_format":{"type":"json_schema"}}
                    """);
            assertThat(fake.rejections.getLast()).contains("deprecated output_format is forbidden");

            post(fake, """
                    {"model":"m","max_tokens":16,"messages":[{"role":"user","content":"probe"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"a","name":"probe_echo","input":{}}]},
                    {"role":"user","content":[{"type":"text","text":"split"}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"{}"}]}]}
                    """);
            assertThat(fake.rejections.getLast())
                    .contains("tool_result message must immediately follow the assistant tool_use message");

            var forgedBeta = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(fake.baseUrl() + "/messages"))
                            .header("Content-Type", "application/json")
                            .header("anthropic-version", "2023-06-01")
                            .header("anthropic-beta", "structured-outputs-2025-11-13")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    "{\"model\":\"m\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}"))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(forgedBeta.statusCode()).isEqualTo(400);
            assertThat(fake.rejections.getLast()).contains("anthropic-beta");
        }
    }

    @Test
    void unqualifiedConnectionFailsClosedForToolsBeforeHttp() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), false);
            assertThatThrownBy(() -> transport(runtime, mapper).complete(target(runtime),
                    toolRequest("auto"), Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("UNSUPPORTED_AGENT_MODEL");
            assertThat(fake.requests).isEmpty();
        }
    }

    @Test
    void legacyConnectionWithoutUuidNeverCarriesTools() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = new RuntimeCustomSettings("ANTHROPIC_MESSAGES", fake.baseUrl(),
                    "fixture-secret", "anthropic-model", 3);
            assertThatThrownBy(() -> transport(runtime, mapper).complete(target(runtime),
                    toolRequest("auto"), Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(fake.requests).isEmpty();
        }
    }

    @Test
    void revisionDriftFailsClosedWithoutProviderCall() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            UUID id = UUID.randomUUID();
            var runtime = settings(fake.baseUrl(), 2, id, true);
            var drifted = new GaModelTarget("CUSTOM", "anthropic-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            assertThatThrownBy(() -> transport(runtime, mapper).complete(drifted, textRequest(),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("GA_MODEL_BINDING_MISMATCH");
            assertThat(fake.requests).isEmpty();
        }
    }

    @Test
    void httpErrorsMapToTypedProviderFailures() throws Exception {
        try (var fake = new FakeAnthropicMessagesProvider()) {
            var runtime = settings(fake.baseUrl(), 1, UUID.randomUUID(), true);
            var transport = transport(runtime, mapper);
            fake.status = 401;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(ModelProviderException.class);
            fake.status = 429;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(ModelProviderException.class);
            fake.status = 500;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(ModelProviderException.class);
            fake.status = 200;
            fake.malformed = true;
            assertThatThrownBy(() -> transport.complete(target(runtime), textRequest(),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(ModelProviderException.class);
        }
    }

    private static void post(FakeAnthropicMessagesProvider fake, String body) throws Exception {
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(fake.baseUrl() + "/messages"))
                        .header("Content-Type", "application/json")
                        .header("anthropic-version", "2023-06-01")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 400) {
            throw new IllegalStateException("strict fake unexpectedly accepted the request: " + body);
        }
    }
}
