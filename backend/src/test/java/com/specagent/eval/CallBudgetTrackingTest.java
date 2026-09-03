package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Call-budget tracking tests (P2 evaluation harness, TDD).
 *
 * <p>Production reasoning calls, provider retries, judge calls, and
 * capability calls are tracked independently. A provider retry is never a
 * new production reasoning step, and a judge call is never a production
 * call. A normal answer cycle expects exactly STATE_UPDATE + DECISION.
 */
class CallBudgetTrackingTest {

    @Test
    void normalCycleRecordsTwoProductionCallsAndNoRetries() {
        CallBudgetTracker tracker = CallBudgetTracker.empty();
        tracker.recordProductionCall("STATE_UPDATE");
        tracker.recordProductionCall("DECISION");

        assertThat(tracker.productionModelCalls()).isEqualTo(2);
        assertThat(tracker.stages()).containsExactly("STATE_UPDATE", "DECISION");
        assertThat(tracker.providerRetries()).isZero();
        assertThat(tracker.judgeModelCalls()).isZero();
        assertThat(tracker.check(CallBudget.normalAnswerCycle())).isEmpty();
    }

    @Test
    void thirdProductionCallViolatesNormalBudget() {
        CallBudgetTracker tracker = CallBudgetTracker.empty();
        tracker.recordProductionCall("STATE_UPDATE");
        tracker.recordProductionCall("DECISION");
        tracker.recordProductionCall("DECISION");

        List<Violation> violations = tracker.check(CallBudget.normalAnswerCycle());

        assertThat(violations).extracting(Violation::failureClass)
                .contains(FailureClass.CALL_BUDGET);
    }

    @Test
    void providerRetryDoesNotCountAsProductionCall() {
        CallBudgetTracker tracker = CallBudgetTracker.empty();
        tracker.recordProductionCall("STATE_UPDATE");
        tracker.recordProviderRetry();
        tracker.recordProductionCall("DECISION");

        assertThat(tracker.productionModelCalls()).isEqualTo(2);
        assertThat(tracker.providerRetries()).isEqualTo(1);
        // The normal cycle allows no retries: the retry itself is tracked
        // separately and breaches the retry dimension only.
        assertThat(tracker.check(CallBudget.normalAnswerCycle()))
                .extracting(Violation::failureClass)
                .containsExactly(FailureClass.CALL_BUDGET);

        CallBudget strict = new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 2, 0, 5, 5);
        List<Violation> violations = tracker.check(strict);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).failureClass()).isEqualTo(FailureClass.CALL_BUDGET);
    }

    @Test
    void judgeCallsNeverCountAsProductionCalls() {
        CallBudgetTracker tracker = CallBudgetTracker.empty();
        tracker.recordProductionCall("STATE_UPDATE");
        tracker.recordProductionCall("DECISION");
        tracker.recordJudgeCall();
        tracker.recordJudgeCall();

        assertThat(tracker.productionModelCalls()).isEqualTo(2);
        assertThat(tracker.judgeModelCalls()).isEqualTo(2);
        assertThat(tracker.check(CallBudget.normalAnswerCycle())).isEmpty();
    }

    @Test
    void capabilityCallsAreTrackedSeparately() {
        CallBudgetTracker tracker = CallBudgetTracker.empty();
        tracker.recordProductionCall("STATE_UPDATE");
        tracker.recordProductionCall("DECISION");
        tracker.recordCapabilityCall();

        assertThat(tracker.capabilityCalls()).isEqualTo(1);
        CallBudget noCapability = new CallBudget(
                List.of("STATE_UPDATE", "DECISION"), 2, 2, 2, 0, 0, 1);
        assertThat(tracker.check(noCapability))
                .extracting(Violation::failureClass)
                .containsExactly(FailureClass.CALL_BUDGET);
    }
}
