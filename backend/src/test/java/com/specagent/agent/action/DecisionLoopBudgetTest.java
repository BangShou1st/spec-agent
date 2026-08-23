package com.specagent.agent.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionLoopBudgetTest {

    @Test
    void defaultBudgetHasReasonableLimits() {
        DecisionLoopBudget budget = DecisionLoopBudget.defaultBudget();
        assertThat(budget.maxDecisionSteps()).isEqualTo(10);
        assertThat(budget.maxModelCallsPerStep()).isEqualTo(2);
    }

    @Test
    void budgetIsImmutable() {
        DecisionLoopBudget budget = new DecisionLoopBudget(5, 1);
        assertThat(budget.maxDecisionSteps()).isEqualTo(5);
        assertThat(budget.maxModelCallsPerStep()).isEqualTo(1);
    }
}
