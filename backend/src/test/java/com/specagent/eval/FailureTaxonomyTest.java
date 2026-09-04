package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure taxonomy tests (P2 evaluation harness, TDD).
 *
 * <p>Every attempt carries typed violations with one overall result. Natural
 * language strings must never scatter across tests as ad-hoc failure labels.
 */
class FailureTaxonomyTest {

    @Test
    void attemptResultAggregatesFailureClasses() {
        AttemptResult result = AttemptResult.failure(
                "E01", "base",
                List.of(
                        new Violation(FailureClass.FORBIDDEN_ACTION, "INVOKE_CAPABILITY executed"),
                        new Violation(FailureClass.UNEXPECTED_STATE_DELTA, "extra node")) ,
                "REQUEST_USER_INPUT",
                CallBudgetTracker.empty());

        assertThat(result.passed()).isFalse();
        assertThat(result.failureClasses())
                .containsExactlyInAnyOrder(FailureClass.FORBIDDEN_ACTION, FailureClass.UNEXPECTED_STATE_DELTA);
        assertThat(result.primaryFailureClass()).isIn(
                FailureClass.FORBIDDEN_ACTION, FailureClass.UNEXPECTED_STATE_DELTA);
    }

    @Test
    void passingAttemptHasNoFailureClass() {
        AttemptResult result = AttemptResult.pass(
                "E01", "base", "REQUEST_USER_INPUT", CallBudgetTracker.empty());

        assertThat(result.passed()).isTrue();
        assertThat(result.failureClasses()).isEmpty();
        assertThat(result.primaryFailureClass()).isNull();
    }

    @Test
    void taxonomyCoversRequiredClasses() {
        assertThat(FailureClass.values()).contains(
                FailureClass.SCENARIO_INVALID,
                FailureClass.RUNTIME_INVARIANT,
                FailureClass.BRAIN_SCHEMA,
                FailureClass.FORBIDDEN_ACTION,
                FailureClass.REQUIRED_PROPERTY_MISSING,
                FailureClass.UNEXPECTED_STATE_DELTA,
                FailureClass.AUTHORIZATION,
                FailureClass.CALL_BUDGET,
                FailureClass.PROVIDER_FAILURE,
                FailureClass.CAPABILITY_FAILURE,
                FailureClass.JUDGE_ONLY);
    }

    @Test
    void providerFailureIsClassifiedOutsideBehavioralFailureCounts() {
        ObservationEnvelope observation = ObservationEnvelope.builder(
                        "E01", "base", "hash", EvaluationProfile.LIVE_PROVIDER)
                .executionResult("failed:AgentBrainUnavailableException: provider unavailable")
                .violations(List.of(new Violation(FailureClass.PROVIDER_FAILURE,
                        "brain unavailable")))
                .build();

        LiveStabilitySummary summary = LiveStabilitySummary.from(
                List.of(observation), 1, 1);

        assertThat(summary.behavioralCompleted()).isZero();
        assertThat(summary.behavioralPassed()).isZero();
        assertThat(summary.behavioralFailed()).isZero();
        assertThat(summary.infrastructureFailed()).isEqualTo(1);
        assertThat(summary.behavioralPassRate()).isZero();
        assertThat(summary.availabilityRate()).isZero();
        assertThat(summary.providerFailureClasses())
                .containsEntry(ProviderFailureClass.BRAIN_UNAVAILABLE, 1);
    }

    @Test
    void allInfrastructureAttemptsCannotProducePerfectBehavioralStability() {
        List<ObservationEnvelope> observations = List.of(
                providerFailure("E01", "base"),
                providerFailure("E01", "base"));

        LiveStabilitySummary summary = LiveStabilitySummary.from(observations, 2, 2);

        assertThat(summary.stability()).isZero();
        assertThat(summary.notes()).anyMatch(note ->
                note.contains("NO_BEHAVIORAL_ATTEMPTS E01/base"));
    }

    @Test
    void deterministicRuntimeFailureIsNotReclassifiedAsProviderFailure() {
        ObservationEnvelope observation = ObservationEnvelope.builder(
                        "E06", "divergent", "hash", EvaluationProfile.LIVE_PROVIDER)
                .executionResult("failed:SHARED_STATE_DIVERGENCE")
                .violations(List.of(new Violation(FailureClass.AUTHORIZATION,
                        "divergence rejected")))
                .build();

        assertThat(LiveFailureClassifier.isInfrastructureFailure(observation)).isFalse();
        assertThat(LiveFailureClassifier.classify(observation)).isNull();
    }

    @Test
    void providerStatusClassesRemainDistinctForReliabilityReporting() {
        assertThat(LiveFailureClassifier.classify(providerFailureWithResult("failed:HTTP 401")))
                .isEqualTo(ProviderFailureClass.UNAUTHORIZED_401);
        assertThat(LiveFailureClassifier.classify(providerFailureWithResult("failed:HTTP 429 rate limit")))
                .isEqualTo(ProviderFailureClass.RATE_LIMIT_429);
        assertThat(LiveFailureClassifier.classify(providerFailureWithResult("failed:HTTP 502")))
                .isEqualTo(ProviderFailureClass.SERVER_5XX);
    }

    @Test
    void schemaFailureCanBeReportedSeparatelyFromProviderTransportFailure() {
        ObservationEnvelope observation = ObservationEnvelope.builder(
                        "E01", "base", "hash", EvaluationProfile.LIVE_PROVIDER)
                .executionResult("failed:ModelContractException")
                .semanticTrace(com.specagent.trace.SemanticTrace.empty(null)
                        .withStage("DECISION_OUTPUT", Map.of(
                                "error_type", "ModelContractException")))
                .build();

        assertThat(LiveFailureClassifier.isSchemaFailure(observation)).isTrue();
        assertThat(LiveFailureClassifier.isInfrastructureFailure(observation)).isFalse();
    }

    private static ObservationEnvelope providerFailure(String scenario, String variant) {
        return ObservationEnvelope.builder(scenario, variant, "hash",
                        EvaluationProfile.LIVE_PROVIDER)
                .executionResult("failed:timeout")
                .violations(List.of(new Violation(FailureClass.PROVIDER_FAILURE,
                        "provider timeout")))
                .build();
    }

    private static ObservationEnvelope providerFailureWithResult(String result) {
        return ObservationEnvelope.builder("E01", "base", "hash",
                        EvaluationProfile.LIVE_PROVIDER)
                .executionResult(result)
                .build();
    }
}
