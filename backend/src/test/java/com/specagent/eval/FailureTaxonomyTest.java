package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
