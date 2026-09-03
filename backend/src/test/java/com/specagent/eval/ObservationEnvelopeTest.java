package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observation-envelope and artifact serialization tests (P2, TDD).
 *
 * <p>Every scenario attempt ends in one uniform observation: pre/post state,
 * actual primary action, execution result, state deltas, invariant/property
 * outcomes, violations, failure class, call/token/cost/latency accounting,
 * and reproducibility metadata. Unknown cost stays {@code unknown} — never
 * estimated. Artifacts serialize to one JSONL line per attempt plus
 * summary.json / summary.txt aggregates.
 */
class ObservationEnvelopeTest {

    private static ObservationEnvelope passingObservation() {
        return ObservationEnvelope.builder("E01", "base", "hash-1", EvaluationProfile.B_FAST)
                .preState(StateSummary.of(1, 1, 0, 0, 0, 0))
                .postState(StateSummary.of(1, 2, 1, 1, 0, 0))
                .actualPrimaryAction("REQUEST_USER_INPUT")
                .executionResult("completed")
                .stateDelta(Map.of("answers", 1, "patches", 1, "nodes", 1))
                .invariantResults(List.of(new CheckResult("GRAPH_INTEGRITY", true, "ok")))
                .propertyResults(List.of(new CheckResult("ANSWER_PERSISTED", true, "ok")))
                .callBudget(CallBudgetTracker.of(2, 0, 0, 0))
                .latencyMs(120L)
                .seed(42L)
                .build();
    }

    @Test
    void passingObservationHasNoViolationsOrFailureClass() {
        ObservationEnvelope observation = passingObservation();

        assertThat(observation.passed()).isTrue();
        assertThat(observation.violations()).isEmpty();
        assertThat(observation.failureClass()).isNull();
        assertThat(observation.cost()).isEqualTo("unknown");
    }

    @Test
    void costIsNeverEstimated() {
        ObservationEnvelope observation = ObservationEnvelope.builder("E01", "base", "hash-1",
                        EvaluationProfile.B_FAST)
                .preState(StateSummary.empty())
                .postState(StateSummary.empty())
                .actualPrimaryAction("WAIT")
                .executionResult("completed")
                .stateDelta(Map.of())
                .callBudget(CallBudgetTracker.empty())
                .build();

        assertThat(observation.cost()).isEqualTo("unknown");
    }

    @Test
    void jsonlRoundTripPreservesRequiredMetadata() {
        ObservationEnvelope observation = passingObservation()
                .withRunMetadata("run-1", "abc123", "provider-x", "model-y", "digest-z");

        String line = EvalArtifactWriter.toJsonl(observation);
        ObservationEnvelope decoded = EvalArtifactWriter.fromJsonl(line);

        assertThat(decoded.scenarioId()).isEqualTo("E01");
        assertThat(decoded.variantId()).isEqualTo("base");
        assertThat(decoded.runId()).isEqualTo("run-1");
        assertThat(decoded.gitSha()).isEqualTo("abc123");
        assertThat(decoded.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(decoded.productionModelCalls()).isEqualTo(2);
        assertThat(decoded.cost()).isEqualTo("unknown");
    }

    @Test
    void summaryAggregatesAttempts() {
        ObservationEnvelope pass = passingObservation();
        ObservationEnvelope fail = ObservationEnvelope.builder("E06", "divergent", "hash-2",
                        EvaluationProfile.B_FAST)
                .preState(StateSummary.empty())
                .postState(StateSummary.empty())
                .actualPrimaryAction("WAIT")
                .executionResult("failed:SHARED_STATE_DIVERGENCE")
                .stateDelta(Map.of())
                .violations(List.of(new Violation(FailureClass.AUTHORIZATION, "divergence rejected")))
                .callBudget(CallBudgetTracker.of(2, 0, 0, 0))
                .build();

        EvalSummary summary = EvalSummary.from(List.of(pass, fail));

        assertThat(summary.totalAttempts()).isEqualTo(2);
        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failureCounts()).containsEntry(FailureClass.AUTHORIZATION, 1);
        assertThat(summary.primaryActionDistribution()).containsEntry("REQUEST_USER_INPUT", 1);
        assertThat(summary.toText()).contains("total=2");
    }
}
