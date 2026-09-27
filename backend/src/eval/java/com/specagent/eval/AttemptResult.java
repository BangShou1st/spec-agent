package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件名:AttemptResult.java
 *
 * 用途:单个场景一次尝试的整体结果:是否通过、实际执行的主动作,以及
 * 带失败分类({@link FailureClass})的违例列表和调用预算记录。
 *
 * 协作:由 {@link ScenarioRunner} 产出,是分层校验器和汇总报告
 * ({@link EvalSummary})的基本数据单元。
 */
public record AttemptResult(
        String scenarioId,
        String variantId,
        boolean passed,
        String actualPrimaryAction,
        List<Violation> violations,
        CallBudgetTracker callBudget) {

    public static AttemptResult pass(String scenarioId, String variantId,
                                     String actualPrimaryAction, CallBudgetTracker callBudget) {
        return new AttemptResult(scenarioId, variantId, true,
                actualPrimaryAction, List.of(), callBudget);
    }

    public static AttemptResult failure(String scenarioId, String variantId,
                                        List<Violation> violations,
                                        String actualPrimaryAction,
                                        CallBudgetTracker callBudget) {
        return new AttemptResult(scenarioId, variantId, false,
                actualPrimaryAction, List.copyOf(violations), callBudget);
    }

    public List<FailureClass> failureClasses() {
        Set<FailureClass> classes = new LinkedHashSet<>();
        for (Violation violation : violations) {
            classes.add(violation.failureClass());
        }
        return new ArrayList<>(classes);
    }

    public FailureClass primaryFailureClass() {
        return violations.isEmpty() ? null : violations.get(0).failureClass();
    }
}
