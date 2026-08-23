package com.specagent.agent.action;

/**
 * Controls the maximum number of decision steps and detects repeated or
 * no-progress actions within a single agent run. Each decision cycle
 * decrements the remaining budget; when exhausted the loop must stop.
 */
public record DecisionLoopBudget(int maxDecisionSteps,
                                 int maxModelCallsPerStep) {

    public static DecisionLoopBudget defaultBudget() {
        return new DecisionLoopBudget(10, 2);
    }
}
