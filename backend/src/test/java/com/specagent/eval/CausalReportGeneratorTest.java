package com.specagent.eval;

import com.specagent.trace.SemanticTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline, model-free tests for the first-fault classifier and report shape. */
class CausalReportGeneratorTest {

    private static final UUID ATTEMPT =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void missingConflictInNormalizedStateUpdateIsStateUpdateFirst() {
        SemanticTrace trace = completeTrace(
                List.of(Map.of("kind", "goal", "status", "confirmed", "text", "goal")),
                List.of(Map.of("kind", "goal", "status", "confirmed", "text", "goal")),
                List.of(Map.of("kind", "goal", "status", "confirmed", "text", "goal")),
                "REQUEST_USER_INPUT");
        ObservationEnvelope observation = failed("E07", "unresolved", trace);

        CausalReportGenerator.CausalReport report = CausalReportGenerator.generate(
                List.of(observation), Map.of("E07", EvalCorpus.e07()));

        assertThat(report.behavioralFailures()).isEqualTo(1);
        assertThat(report.firstFaultMatrix())
                .containsEntry(CausalReportGenerator.FirstFault.STATE_UPDATE_FIRST, 1);
        assertThat(report.symptomMatrix()).containsKey("conflict missing");
        assertThat(String.valueOf(report.toMap())).contains("first_fault_matrix");
    }

    @Test
    void wrongActionAfterEquivalentSemanticPropagationIsDecisionFirst() {
        List<Map<String, Object>> claims = List.of(
                Map.of("kind", "goal", "status", "confirmed", "text", "goal"));
        SemanticTrace trace = completeTrace(claims, claims, claims, "CREATE_NODE");
        ObservationEnvelope observation = failed("E19", "large", trace);

        CausalReportGenerator.CausalReport report = CausalReportGenerator.generate(
                List.of(observation), Map.of("E19", EvalCorpusBatch2.e19()));

        assertThat(report.firstFaultMatrix())
                .containsEntry(CausalReportGenerator.FirstFault.DECISION_FIRST, 1);
        assertThat(report.symptomMatrix()).containsKey("CN instead of RUI");
    }

    @Test
    void capturedBoundaryFailuresAreClassifiedBeforeIncompleteTrace() {
        SemanticTrace trace = SemanticTrace.empty(ATTEMPT)
                .withStage("STATE_UPDATE_INPUT", Map.of())
                .withStage("STATE_UPDATE_OUTPUT", Map.of(
                        "normalized_output", Map.of("claims", List.of())))
                .withStage("STATE_APPLICATION", Map.of(
                        "error_type", "ModelContractException",
                        "error_summary", "patch rejected"))
                .withStage("FINAL_RESULT", Map.of("evaluation_pass", false));
        ObservationEnvelope observation = failed("E19", "large", trace);

        CausalReportGenerator.CausalReport report = CausalReportGenerator.generate(
                List.of(observation), Map.of("E19", EvalCorpusBatch2.e19()));

        assertThat(report.firstFaultMatrix())
                .containsEntry(CausalReportGenerator.FirstFault.STATE_APPLICATION_FIRST, 1);
    }

    @Test
    void multipleCapturedBoundaryFailuresRemainCompound() {
        SemanticTrace trace = SemanticTrace.empty(ATTEMPT)
                .withStage("STATE_UPDATE_OUTPUT", Map.of("error_type", "BrainContractError"))
                .withStage("DECISION_OUTPUT", Map.of("error_type", "ModelContractException"));
        ObservationEnvelope observation = failed("E19", "large", trace);

        CausalReportGenerator.CausalReport report = CausalReportGenerator.generate(
                List.of(observation), Map.of("E19", EvalCorpusBatch2.e19()));

        assertThat(report.firstFaultMatrix())
                .containsEntry(CausalReportGenerator.FirstFault.COMPOUND, 1);
    }

    @Test
    void infrastructureAttemptIsNotReportedAsPassedEvenWithoutDerivedViolations() {
        ObservationEnvelope observation = ObservationEnvelope.builder(
                        "E01", "base", "hash", EvaluationProfile.LIVE_PROVIDER)
                .executionResult("failed:timeout")
                .semanticTrace(SemanticTrace.empty(ATTEMPT))
                .build();

        CausalReportGenerator.CausalReport report = CausalReportGenerator.generate(
                List.of(observation), Map.of("E01", EvalCorpus.e01()));

        assertThat(report.behavioralFailures()).isZero();
        assertThat(report.infrastructureFailures()).isEqualTo(1);
        assertThat(report.attempts().get(0).passed()).isFalse();
        assertThat(report.attempts().get(0).infrastructureFailure()).isTrue();
    }

    private static ObservationEnvelope failed(String scenario, String variant,
                                              SemanticTrace trace) {
        return ObservationEnvelope.builder(scenario, variant, "hash", EvaluationProfile.LIVE_PROVIDER)
                .actualPrimaryAction("CREATE_NODE")
                .executionResult("completed")
                .stateDelta(Map.of("nodes", 1))
                .violations(List.of(new Violation(FailureClass.FORBIDDEN_ACTION,
                        "primary action is not acceptable")))
                .semanticTrace(trace)
                .build();
    }

    private static SemanticTrace completeTrace(List<Map<String, Object>> updateClaims,
                                               List<Map<String, Object>> postClaims,
                                               List<Map<String, Object>> decisionClaims,
                                               String action) {
        Map<String, Object> input = Map.of(
                "model_input", Map.of("snapshot", Map.of("effectiveClaims", decisionClaims)));
        return SemanticTrace.empty(ATTEMPT)
                .withStage("STATE_UPDATE_INPUT", input)
                .withStage("STATE_UPDATE_OUTPUT", Map.of(
                        "normalized_output", Map.of("claims", updateClaims)))
                .withStage("POST_STATE_UPDATE_STATE", Map.of(
                        "effective_claims", postClaims))
                .withStage("DECISION_INPUT", input)
                .withStage("DECISION_OUTPUT", Map.of(
                        "normalized_output", Map.of(
                                "actionProposal", Map.of("actionFamily", action))))
                .withStage("FINAL_RESULT", Map.of("evaluation_pass", false));
    }
}
