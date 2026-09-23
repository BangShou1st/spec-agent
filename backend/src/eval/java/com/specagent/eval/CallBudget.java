package com.specagent.eval;

import java.util.List;

/**
 * Declarative call-budget contract of one scenario.
 *
 * <p>Production reasoning calls, provider retries, judge calls, and
 * capability calls are budgeted independently: a provider retry is never a
 * new production reasoning step, and a judge call is never a production
 * call. A normal answer cycle expects exactly STATE_UPDATE + DECISION, but
 * short-circuit / fail-closed scenarios may legitimately declare different
 * budgets — never hardcode "exactly 2 calls" for every scenario.
 */
public record CallBudget(
        List<String> expectedStages,
        int minProductionCalls,
        int maxProductionCalls,
        int maxStateUpdateCalls,
        int maxProviderRetries,
        int maxCapabilityCalls,
        int maxJudgeCalls) {

    public CallBudget {
        expectedStages = expectedStages == null ? List.of() : List.copyOf(expectedStages);
    }

    /** Normal successful answer cycle: STATE_UPDATE + DECISION, no retries. */
    public static CallBudget normalAnswerCycle() {
        return new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 5, 5);
    }
}
