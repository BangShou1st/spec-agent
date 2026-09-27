package com.specagent.eval;

import java.util.List;

/**
 * 文件名:CallBudget.java
 *
 * 用途:单个场景的声明式调用预算契约。生产推理调用、provider 重试、
 * judge 调用和能力(capability)调用分别独立预算:provider 重试不算新的
 * 生产推理步骤,judge 调用也不算生产调用。正常回答周期恰好是
 * STATE_UPDATE + DECISION 两步,但短路 / fail-closed 场景可以合法声明不同
 * 的预算——不要给所有场景硬编码"恰好 2 次调用"。
 *
 * 协作:由 {@link ScenarioDefinition} 声明,执行后由
 * {@link CallBudgetTracker} 实测计数并与预算比对。
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

    /** 正常成功的回答周期:STATE_UPDATE + DECISION,无重试。 */
    public static CallBudget normalAnswerCycle() {
        return new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 5, 5);
    }
}
