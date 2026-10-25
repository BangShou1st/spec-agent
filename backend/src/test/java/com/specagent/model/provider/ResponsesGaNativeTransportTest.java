package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.support.FakeResponsesProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MS-C2 protocol contract: request shape, call_id identity and fail-closed parsing. */
class ResponsesGaNativeTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static RuntimeCustomSettings settings(String baseUrl, long revision, UUID id, boolean nativeTools) {
        return new RuntimeCustomSettings("RESPONSES", baseUrl, "fixture-secret",
                "responses-model", revision, id, nativeTools);
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

    private static GaModelContract.Request textRequest() {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "你好", List.of(), null)),
                List.of(), "none", 256, false);
    }

    private static GaModelContract.Request toolRequest(String choice) {
        return new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                List.of(new GaModelContract.Message("user", "probe", List.of(), null)),
                List.of(new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"))),
                choice, 256, false);
    }

    @Test
    void chatUsesResponsesInputAndStoreFalse() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            var response = transport.complete(target, textRequest(), Duration.ofSeconds(30), () -> true);

            assertThat(response.content()).isEqualTo("responses final answer");
            assertThat(fake.requests).hasSize(1);
            assertThat(fake.requests.getFirst().path("model").asText()).isEqualTo("responses-model");
            assertThat(fake.requests.getFirst().path("input").get(0).path("role").asText()).isEqualTo("user");
            assertThat(fake.requests.getFirst().has("messages")).isFalse();
            assertThat(fake.requests.getFirst().path("store").isBoolean()).isTrue();
            assertThat(fake.requests.getFirst().path("store").asBoolean()).isFalse();
            assertThat(fake.requests.getFirst().has("previous_response_id")).isFalse();
            assertThat(fake.requests.getFirst().has("tools")).isFalse();
            assertThat(fake.requests.getFirst().path("max_output_tokens").asInt()).isEqualTo(256);
            assertThat(fake.keys).containsOnly("Bearer fixture-secret");
        }
    }

    @Test
    void functionCallRoundTripPreservesCallIdAndOrder() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            var first = transport.complete(target, toolRequest("required"), Duration.ofSeconds(30), () -> true);
            assertThat(first.toolCalls()).hasSize(1);
            assertThat(first.finishReason()).isEqualTo("tool_calls");
            String callId = first.toolCalls().getFirst().id();

            var assistant = new GaModelContract.Message("assistant", "", List.of(
                    new GaModelContract.ToolCall(callId, "probe_echo", Map.of("text", "probe ok"))), null);
            var tool = new GaModelContract.Message("tool", "{\"status\":\"SUCCEEDED\"}", List.of(), callId);
            var followUp = new GaModelContract.Request(GaModelContract.VERSION, UUID.randomUUID(), 1,
                    UUID.randomUUID(), UUID.randomUUID(), "AGENT", UUID.randomUUID(),
                    List.of(new GaModelContract.Message("user", "probe", List.of(), null), assistant, tool),
                    List.of(new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"))),
                    "none", 256, false);
            var second = transport.complete(target, followUp, Duration.ofSeconds(30), () -> true);

            assertThat(second.content()).isEqualTo("responses final answer");
            assertThat(second.toolCalls()).isEmpty();
            var followUpBody = fake.requests.get(1);
            JsonNode historyCall = followUpBody.path("input").get(1);
            assertThat(historyCall.path("type").asText()).isEqualTo("function_call");
            assertThat(historyCall.has("role")).isFalse();
            assertThat(historyCall.has("tool_calls")).isFalse();
            assertThat(historyCall.path("call_id").asText()).isEqualTo(callId);
            assertThat(historyCall.path("name").asText()).isEqualTo("probe_echo");
            assertThat(historyCall.path("arguments").asText())
                    .isEqualTo("{\"text\":\"probe ok\"}");
            var output = followUpBody.path("input").get(2);
            assertThat(output.path("type").asText()).isEqualTo("function_call_output");
            assertThat(output.path("call_id").asText()).isEqualTo(callId);
            assertThat(output.path("output").asText()).isEqualTo("{\"status\":\"SUCCEEDED\"}");
            assertThat(followUpBody.path("store").asBoolean()).isFalse();
        }
    }

    @Test
    void strictFakeRejectsChatCompletionsToolHistoryShape() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            RuntimeCustomSettings settings = settings(fake.baseUrl(), 1, id, true);
            var transport = new ResponsesGaNativeTransport(port(settings), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");

            // Post a raw legacy body directly to the fake: the fixed adapter can no
            // longer produce this shape, and the fixture must keep rejecting it so
            // regressions cannot hide behind a permissive fake.
            var legacy = mapper.createObjectNode();
            legacy.put("model", "responses-model");
            var input = legacy.putArray("input");
            var user = input.addObject();
            user.put("role", "user");
            user.put("content", "probe");
            var assistant = input.addObject();
            assistant.put("role", "assistant");
            assistant.put("content", "");
            var embedded = assistant.putArray("tool_calls").addObject();
            embedded.put("call_id", "legacy-call");
            embedded.put("name", "probe_echo");
            embedded.put("arguments", "{\"text\":\"probe ok\"}");
            var output = input.addObject();
            output.put("type", "function_call_output");
            output.put("call_id", "legacy-call");
            output.put("output", "{\"ok\":true}");
            legacy.put("store", false);
            legacy.put("tool_choice", "none");
            legacy.put("max_output_tokens", 256);
            var posted = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(fake.baseUrl() + "/responses"))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(legacy))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            assertThat(posted.statusCode()).isEqualTo(400);
            assertThat(fake.rejections).isNotEmpty();
            assertThat(fake.rejections.getFirst())
                    .contains("assistant tool_calls is chat-completions shape");
            assertThat(posted.body()).contains("protocol violation");
        }
    }

    @Test
    void twoReadOnlyToolCallsExpandToIndependentMatchedItems() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            fake.multiTool = true;
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            var tool = new GaModelContract.Tool("probe_echo", "echo", Map.of("type", "object"));
            var user = new GaModelContract.Message("user", "probe twice", List.of(), null);
            var first = transport.complete(target, new GaModelContract.Request(
                    GaModelContract.VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                    UUID.randomUUID(), "AGENT", UUID.randomUUID(), List.of(user),
                    List.of(tool), "required", 256, false),
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
                    List.of(tool), "none", 256, false);
            var second = transport.complete(target, followUp, Duration.ofSeconds(30), () -> true);

            assertThat(second.content()).isEqualTo("responses final answer");
            var input = fake.requests.get(1).path("input");
            assertThat(input.get(1).path("type").asText()).isEqualTo("function_call");
            assertThat(input.get(1).path("call_id").asText()).isEqualTo(firstCall.id());
            assertThat(input.get(2).path("type").asText()).isEqualTo("function_call");
            assertThat(input.get(2).path("call_id").asText()).isEqualTo(secondCall.id());
            assertThat(input.get(3).path("type").asText()).isEqualTo("function_call_output");
            assertThat(input.get(3).path("call_id").asText()).isEqualTo(firstCall.id());
            assertThat(input.get(4).path("type").asText()).isEqualTo("function_call_output");
            assertThat(input.get(4).path("call_id").asText()).isEqualTo(secondCall.id());
            assertThat(fake.rejections).isEmpty();
        }
    }

    @Test
    void strictFakeRejectsOrphanMismatchedAndUnorderedToolHistory() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            post(fake, """
                    {"model":"m","input":[{"role":"user","content":"probe"},
                    {"type":"function_call_output","call_id":"ghost","output":"{}"}],
                    "store":false,"tool_choice":"none","max_output_tokens":16}
                    """);
            assertThat(fake.rejections.getLast()).contains("orphan function_call_output");

            post(fake, """
                    {"model":"m","input":[{"role":"user","content":"probe"},
                    {"type":"function_call","call_id":"a","name":"probe_echo","arguments":"{}"},
                    {"type":"function_call_output","call_id":"other","output":"{}"}],
                    "store":false,"tool_choice":"none","max_output_tokens":16}
                    """);
            assertThat(fake.rejections.getLast()).contains("orphan function_call_output");

            post(fake, """
                    {"model":"m","input":[{"role":"user","content":"probe"},
                    {"type":"function_call","call_id":"a","name":"probe_echo","arguments":"{}"},
                    {"role":"assistant","content":"final before tools finished"}],
                    "store":false,"tool_choice":"none","max_output_tokens":16}
                    """);
            assertThat(fake.rejections.getLast())
                    .contains("function_call without output");

            post(fake, """
                    {"model":"m","input":[{"role":"assistant","content":"done"},
                    {"type":"function_call","call_id":"a","name":"probe_echo","arguments":"{}"}],
                    "store":false,"tool_choice":"none","max_output_tokens":16}
                    """);
            assertThat(fake.rejections.getLast())
                    .contains("final assistant text appeared before tool history ended");
        }
    }

    private static void post(FakeResponsesProvider fake, String body) throws Exception {
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(fake.baseUrl() + "/responses"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 400) {
            throw new IllegalStateException("strict fake unexpectedly accepted the request");
        }
    }

    @Test
    void unqualifiedConnectionFailsClosedForToolsBeforeHttp() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, false)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            assertThatThrownBy(() -> transport.complete(target, toolRequest("auto"),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("UNSUPPORTED_AGENT_MODEL");
            assertThat(fake.requests).isEmpty();
        }
    }

    @Test
    void malformedIncompleteAndDuplicateCallShapesFailClosed() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");

            fake.malformed = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(ModelProviderException.class);
            fake.malformed = false;
            fake.status = 200;
            fake.incomplete = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.incomplete = false;
            fake.duplicateCallId = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.duplicateCallId = false;
            fake.wrongToolName = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
            fake.wrongToolName = false;
            fake.refuse = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void mixedTextAndFunctionCallOutputIsRejectedBeforeReleaseRegardlessOfOrder() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 1, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");

            fake.mixedTextAndToolOutput = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mixed Responses text and function calls");

            fake.mixedTextAndToolOutput = false;
            fake.mixedToolBeforeText = true;
            assertThatThrownBy(() -> transport.complete(target, toolRequest("required"),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mixed Responses text and function calls");
        }
    }

    @Test
    void revisionDriftFailsClosedWithoutProviderCall() throws Exception {
        try (var fake = new FakeResponsesProvider()) {
            UUID id = UUID.randomUUID();
            var transport = new ResponsesGaNativeTransport(
                    port(settings(fake.baseUrl(), 2, id, true)), mapper,
                    java.net.http.HttpClient.newHttpClient());
            var target = new GaModelTarget("CUSTOM", "responses-model",
                    "custom-provider:" + id + ":1", "fixture-secret");
            assertThatThrownBy(() -> transport.complete(target, textRequest(),
                    Duration.ofSeconds(30), () -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("GA_MODEL_BINDING_MISMATCH");
            assertThat(fake.requests).isEmpty();
        }
    }
}
