package com.specagent.model.inference;

import com.specagent.model.provider.OpenCodeChatCompletionRequest;
import com.specagent.model.provider.OpenCodeCompletionResponse;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
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
        OpenCodeSettingsService settings = mock(OpenCodeSettingsService.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "gpt-5.6-terra", EXTERNAL_SOURCE));
        when(transport.complete(eq("test-key"), any(OpenCodeChatCompletionRequest.class)))
                .thenReturn(new OpenCodeCompletionResponse("{}", "stop", 1, 1, 2));

        ModelInferenceResponse response = new OpenCodeModelInferenceGateway(
                transport, settings).complete(request());

        assertThat(response.content()).isEqualTo("{}");
        verify(transport).complete(eq("test-key"), any(OpenCodeChatCompletionRequest.class));
    }

    @Test
    void productDatabaseSettingsRemainFreeOnly() {
        OpenCodeZenTransport transport = mock(OpenCodeZenTransport.class);
        OpenCodeSettingsService settings = mock(OpenCodeSettingsService.class);
        when(settings.requireRuntimeSettings()).thenReturn(new RuntimeOpenCodeSettings(
                "test-key", "gpt-5.6-terra", "database:opencode_settings"));

        assertThatThrownBy(() -> new OpenCodeModelInferenceGateway(transport, settings)
                .complete(request()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("requires a free model");
    }

    private static ModelInferenceRequest request() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }
}
