package com.specagent.model.inference;

import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCodeModelInferenceGatewayTest {

    private static final String EXTERNAL_SOURCE =
            "external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY";

    @Test
    void externalEvaluationMayUseAnExactNonFreeReferenceModel() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "gpt-5.6-terra", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        ModelInferenceResponse response = new OpenCodeModelInferenceGateway(
                transport, settings).complete(request());

        assertThat(response.content()).isEqualTo("{}");
        verify(transport).complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class));
    }

    @Test
    void gatewayReusesOneSessionForEveryRequestOfTheSameProject() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class),
                any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        var gateway = new OpenCodeModelInferenceGateway(transport, settings);
        // Same project, different runs: the provider must see ONE conversation.
        gateway.complete(request(runId, projectId));
        gateway.complete(request(UUID.randomUUID(), projectId));

        verify(transport, org.mockito.Mockito.times(2)).complete(eq("test-key"), captor.capture(),
                any(OpenCodeChatCompletionRequest.class));
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0)).isNotBlank().startsWith("ses_");
        assertThat(captor.getAllValues().get(1)).isEqualTo(captor.getAllValues().get(0));
    }

    @Test
    void differentProjectsGetDifferentSessions() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class),
                any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        var gateway = new OpenCodeModelInferenceGateway(transport, settings);
        gateway.complete(request(UUID.randomUUID(), UUID.randomUUID()));
        gateway.complete(request(UUID.randomUUID(), UUID.randomUUID()));

        verify(transport, org.mockito.Mockito.times(2)).complete(eq("test-key"), captor.capture(),
                any(OpenCodeChatCompletionRequest.class));
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0)).isNotEqualTo(captor.getAllValues().get(1));
    }

    @Test
    void differentRunsGetDifferentSessions() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class),
                any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        var gateway = new OpenCodeModelInferenceGateway(transport, settings);
        gateway.complete(request());
        gateway.complete(request());

        verify(transport, org.mockito.Mockito.times(2)).complete(eq("test-key"), captor.capture(),
                any(OpenCodeChatCompletionRequest.class));
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0)).isNotBlank().startsWith("ses_");
        assertThat(captor.getAllValues().get(1)).isNotBlank().startsWith("ses_");
        assertThat(captor.getAllValues().get(0)).isNotEqualTo(captor.getAllValues().get(1));
    }

    @Test
    void databaseSettingsMayUseAnyLiveListQualifiedModel() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "gpt-5.6-terra", "database:opencode_settings"));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        // Selection was already gated against the live provider list at
        // save time; the runtime path no longer re-imposes a cost policy.
        assertThat(new OpenCodeModelInferenceGateway(transport, settings)
                .complete(request()).content()).isEqualTo("{}");
    }

    @Test
    void textContractSendsNoResponseFormat() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        ModelInferenceRequest inference = new ModelInferenceRequest(UUID.randomUUID(),
                "GLOBAL_ASSISTANT_DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), 1024,
                ModelOutputContract.text());
        new OpenCodeModelInferenceGateway(transport, settings).complete(inference);

        var captor = org.mockito.ArgumentCaptor.forClass(OpenCodeChatCompletionRequest.class);
        verify(transport).complete(eq("test-key"), any(String.class), captor.capture());
        assertThat(captor.getValue().responseFormat()).isNull();
    }

    @Test
    void jsonSchemaContractMapsToProviderResponseFormat() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ModelInferenceRequest inference = new ModelInferenceRequest(UUID.randomUUID(),
                "GLOBAL_ASSISTANT_DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), 1024,
                ModelOutputContract.jsonSchema("decision", schema));
        new OpenCodeModelInferenceGateway(transport, settings).complete(inference);

        var captor = org.mockito.ArgumentCaptor.forClass(OpenCodeChatCompletionRequest.class);
        verify(transport).complete(eq("test-key"), any(String.class), captor.capture());
        java.util.Map<String, Object> format = captor.getValue().responseFormat();
        assertThat(format.get("type")).isEqualTo("json_schema");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> wrapper =
                (java.util.Map<String, Object>) format.get("json_schema");
        assertThat(wrapper.get("name")).isEqualTo("decision");
        assertThat(wrapper.get("strict")).isEqualTo(Boolean.TRUE);
        assertThat(wrapper.get("schema")).isEqualTo(schema);
    }

    @Test
    void jsonObjectContractMapsToJsonObjectResponseFormat() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        ModelInferenceRequest inference = new ModelInferenceRequest(UUID.randomUUID(),
                "GLOBAL_ASSISTANT_DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), 1024,
                ModelOutputContract.jsonObject());
        new OpenCodeModelInferenceGateway(transport, settings).complete(inference);

        var captor = org.mockito.ArgumentCaptor.forClass(OpenCodeChatCompletionRequest.class);
        verify(transport).complete(eq("test-key"), any(String.class), captor.capture());
        java.util.Map<String, Object> format = captor.getValue().responseFormat();
        assertThat(format.get("type")).isEqualTo("json_object");
        assertThat(format).doesNotContainKey("json_schema");
    }

    @Test
    void outputModesDoNotSilentlyFallback() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "some-free", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(String.class), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));
        var gateway = new OpenCodeModelInferenceGateway(transport, settings);
        var captor = org.mockito.ArgumentCaptor.forClass(OpenCodeChatCompletionRequest.class);
        gateway.complete(new ModelInferenceRequest(UUID.randomUUID(), "D",
                List.of(new ModelInferenceMessage("user", "{}")), 64, ModelOutputContract.text()));
        gateway.complete(new ModelInferenceRequest(UUID.randomUUID(), "D",
                List.of(new ModelInferenceMessage("user", "{}")), 64, ModelOutputContract.jsonObject()));
        java.util.Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        gateway.complete(new ModelInferenceRequest(UUID.randomUUID(), "D",
                List.of(new ModelInferenceMessage("user", "{}")), 64,
                ModelOutputContract.jsonSchema("decision", schema)));
        verify(transport, org.mockito.Mockito.times(3)).complete(eq("test-key"), any(String.class), captor.capture());
        assertThat(captor.getAllValues().get(0).responseFormat()).isNull();
        assertThat(captor.getAllValues().get(1).responseFormat().get("type")).isEqualTo("json_object");
        assertThat(captor.getAllValues().get(2).responseFormat().get("type")).isEqualTo("json_schema");
    }

    private static ModelInferenceRequest request() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }

    private static ModelInferenceRequest request(UUID runId, UUID conversationId) {
        return new ModelInferenceRequest(runId, "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null,
                ModelOutputContract.text(), conversationId);
    }
}
