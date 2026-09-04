package com.specagent.eval;

import com.specagent.trace.SemanticTraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same deterministic scenario, with semantic tracing disabled and enabled.
 * The only tolerated differences are runtime-generated UUIDs/context hashes;
 * all observable behavior and the exact serialized model request remain the
 * same after those identities are normalized for comparison.
 */
class SemanticTraceBehaviorPreservationIntegrationTest extends EvalHarnessBase {

    @Autowired
    private SemanticTraceRecorder semanticTraceRecorder;

    @AfterEach
    void restoreTraceSetting() {
        semanticTraceRecorder.setEnabledForTesting(true);
    }

    @Test
    void tracingDoesNotChangeRequestsCallsStateActionsOrEvaluation() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        semanticTraceRecorder.setEnabledForTesting(false);
        ObservationEnvelope off = runScenario(scenario, variant);
        List<String> offRequests = scriptedBrain.requestPayloads();
        java.util.UUID offProject = scenarioRunner.lastProjectId();
        cleanUpProject(offProject);

        semanticTraceRecorder.setEnabledForTesting(true);
        ObservationEnvelope on = runScenario(scenario, variant);
        List<String> onRequests = scriptedBrain.requestPayloads();

        assertThat(onRequests.stream().map(this::normalizeDynamicIdentity).toList())
                .containsExactlyElementsOf(offRequests.stream()
                        .map(this::normalizeDynamicIdentity).toList());
        assertThat(on.productionModelCalls()).isEqualTo(off.productionModelCalls());
        assertThat(on.providerRetries()).isEqualTo(off.providerRetries());
        assertThat(on.actualPrimaryAction()).isEqualTo(off.actualPrimaryAction());
        assertThat(on.executionResult()).isEqualTo(off.executionResult());
        assertThat(on.stateDelta()).isEqualTo(off.stateDelta());
        assertThat(on.violations()).isEqualTo(off.violations());
        assertThat(off.semanticTrace().stages()).isEmpty();
        assertThat(on.semanticTrace().stages()).containsKeys(
                "STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "POST_STATE_UPDATE_STATE", "DECISION_INPUT", "DECISION_OUTPUT",
                "FINAL_RESULT");
    }

    private String normalizeDynamicIdentity(String value) {
        return value.replaceAll(
                        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "<uuid>")
                .replaceAll("(?i)[0-9a-f]{64}", "<hash>");
    }
}
