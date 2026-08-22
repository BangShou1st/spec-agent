package com.specagent.agent.contract;

/**
 * Runtime-owned loop budget for one decision cycle. The brain must stay
 * within it; the runtime enforces it independently.
 */
public record DecisionBudget(int maxModelCalls) {
}
