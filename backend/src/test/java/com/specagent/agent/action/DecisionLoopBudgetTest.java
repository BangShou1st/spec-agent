package com.specagent.agent.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:DecisionLoopBudgetTest.java
 *
 * 测试目标:验证 DecisionLoopBudget 的默认预算(最多 10 个决策步、每步最多 2 次模型调用)
 * 以及预算对象创建后的不可变性。
 */
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
