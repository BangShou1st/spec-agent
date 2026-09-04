package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the production answer-cycle boundary exposes the semantic
 * evidence needed for causal analysis, using the unchanged deterministic
 * scripted brain and unchanged scenario contracts.
 */
class SemanticTraceCompletenessIntegrationTest extends EvalHarnessBase {

    @Test
    void conflictEvidenceIsVisibleAcrossUpdatePostStateAndDecisionInput() {
        ObservationEnvelope observation = runScenario(
                EvalCorpus.e07(), EvalCorpus.e07().variants().get(0));
        Map<String, Map<String, Object>> stages = observation.semanticTrace().stages();

        assertThat(stages).containsKeys("STATE_UPDATE_INPUT", "STATE_UPDATE_OUTPUT",
                "POST_STATE_UPDATE_STATE", "DECISION_INPUT", "DECISION_OUTPUT",
                "POLICY_DECISION", "FINAL_RESULT");
        assertThat(String.valueOf(stages.get("STATE_UPDATE_OUTPUT")))
                .contains("conflict").contains("unresolved");
        assertThat(String.valueOf(stages.get("POST_STATE_UPDATE_STATE")))
                .contains("conflict").contains("unresolved");
        assertThat(String.valueOf(stages.get("DECISION_INPUT")))
                .contains("effectiveClaims").contains("conflict").contains("unresolved");
    }

    @Test
    void resolvedInformationIsObservableWithoutAssumingTheDecisionResult() {
        ScenarioDefinition scenario = EvalCorpus.e07Resolved();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));
        Map<String, Map<String, Object>> stages = observation.semanticTrace().stages();
        String update = String.valueOf(stages.get("STATE_UPDATE_OUTPUT"));
        String post = String.valueOf(stages.get("POST_STATE_UPDATE_STATE"));
        String decisionInput = String.valueOf(stages.get("DECISION_INPUT"));

        assertThat(update).contains("e07r-decided").contains("confirmed");
        assertThat(post).contains("e07r-decided").contains("confirmed");
        assertThat(decisionInput).contains("e07r-decided").contains("confirmed");
    }

    @Test
    void waitInputIsTraceableAtTheDecisionBoundary() {
        ScenarioDefinition wait = EvalCorpusBatch2.e22Wait();
        ObservationEnvelope waitObservation = runScenario(wait, wait.variants().get(0));
        assertThat(String.valueOf(waitObservation.semanticTrace().stages()
                .get("DECISION_INPUT"))).contains("effectiveClaims").contains("e22-claim");
    }

    @Test
    void confirmationPolicyIsTraceableAtTheDecisionBoundary() {
        ScenarioDefinition confirmation = EvalCorpus.e17();
        ObservationEnvelope confirmationObservation = runScenario(
                confirmation, confirmation.variants().get(0));
        assertThat(String.valueOf(confirmationObservation.semanticTrace().stages()
                .get("DECISION_INPUT"))).contains("LOCAL_DURABLE")
                .contains("eval.high-risk.local-durable");
        assertThat(confirmationObservation.semanticTrace().stages()
                .get("POLICY_DECISION")).containsEntry("requires_confirmation", true);
    }
}
