package com.specagent.eval;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.LocalDeterministicDecisionEngine;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceMessage;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.OpenCodeModelInferenceGateway;
import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.testing.FakeModelInferenceGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression guards for the boundary between B-fast and B-live. */
class LiveExecutionGuardTest {

    private final AgentDecisionEngine remoteBrain =
            new RemotePythonDecisionEngine(new AgentBrainProperties());
    private final ModelInferenceGateway realProvider =
            new OpenCodeModelInferenceGateway(null, null);

    @Test
    void javaFakeEngineIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                new LocalDeterministicDecisionEngine(), realProvider, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentDecisionEngine")
                .hasMessageContaining("RemotePythonDecisionEngine");
    }

    @Test
    void scriptedBrainIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, realProvider, new ScriptedBrain()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScriptedBrain");
    }

    @Test
    void fakeInferenceGatewayIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, new FakeModelInferenceGateway(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ModelInferenceGateway")
                .hasMessageContaining("fake providers are not live");
    }

    @Test
    void remotePythonAndOpenCodeGatewayAreAccepted() {
        LiveExecutionGuard.Evidence evidence = LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, realProvider, null);

        assertThat(evidence.decisionEngine())
                .isEqualTo(RemotePythonDecisionEngine.class.getName());
        assertThat(evidence.inferenceGateway())
                .isEqualTo(OpenCodeModelInferenceGateway.class.getName());
    }

    @Test
    void missingBeansAreRejectedRatherThanTreatedAsFallback() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected RemotePythonDecisionEngine");
    }

    @Test
    void missingProviderConfigurationFailsClosedWithoutFakeFallback() {
        OpenCodeSettingsService settings = mock(OpenCodeSettingsService.class);
        when(settings.requireRuntimeSettings()).thenThrow(
                new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                        "provider is not configured"));
        OpenCodeModelInferenceGateway gateway =
                new OpenCodeModelInferenceGateway(null, settings);

        assertThatThrownBy(() -> gateway.complete(new ModelInferenceRequest(
                java.util.UUID.randomUUID(), "DECISION",
                java.util.List.of(new ModelInferenceMessage("user", "test")), 128)))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(error -> assertThat(((OpenCodeModelException) error).gatewayCategory())
                        .isEqualTo(ModelGatewayErrorCategory.NOT_CONFIGURED));
    }
}
