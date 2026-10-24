package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            assertThat(followUpBody.path("input").get(1).path("tool_calls").get(0)
                    .path("call_id").asText()).isEqualTo(callId);
            var output = followUpBody.path("input").get(2);
            assertThat(output.path("type").asText()).isEqualTo("function_call_output");
            assertThat(output.path("call_id").asText()).isEqualTo(callId);
            assertThat(followUpBody.path("store").asBoolean()).isFalse();
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
