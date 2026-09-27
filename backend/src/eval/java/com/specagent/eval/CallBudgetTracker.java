package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:CallBudgetTracker.java
 *
 * 用途:对一次尝试中生产推理调用、provider 重试、judge 调用和能力调用
 * 做独立计数记账,并与 {@link CallBudget} 声明的预算逐维度比对,超支即产出
 * CALL_BUDGET 类型的 {@link Violation}。
 *
 * 协作:由 {@link ScenarioRunner} 在执行期间记录各类调用,尝试结束后
 * 调用 {@link #check} 生成违例,并随 {@link AttemptResult} 一起上报。
 */
public final class CallBudgetTracker {

    private final List<String> stages = new ArrayList<>();
    private int providerRetries;
    private int judgeModelCalls;
    private int capabilityCalls;

    private CallBudgetTracker(int productionCalls, int providerRetries,
                              int judgeModelCalls, int capabilityCalls) {
        for (int i = 0; i < productionCalls; i++) {
            stages.add("PRODUCTION_CALL");
        }
        this.providerRetries = providerRetries;
        this.judgeModelCalls = judgeModelCalls;
        this.capabilityCalls = capabilityCalls;
    }

    public static CallBudgetTracker empty() {
        return new CallBudgetTracker(0, 0, 0, 0);
    }

    /** 从计数重建但不保留阶段明细(用于产物回放)。 */
    public static CallBudgetTracker of(int productionCalls, int providerRetries,
                                       int judgeModelCalls, int capabilityCalls) {
        return new CallBudgetTracker(productionCalls, providerRetries, judgeModelCalls, capabilityCalls);
    }

    public void recordProductionCall(String stage) {
        stages.add(stage);
    }

    public void recordProviderRetry() {
        providerRetries++;
    }

    public void recordJudgeCall() {
        judgeModelCalls++;
    }

    public void recordCapabilityCall() {
        capabilityCalls++;
    }

    public int productionModelCalls() {
        return stages.size();
    }

    public List<String> stages() {
        return List.copyOf(stages);
    }

    public int providerRetries() {
        return providerRetries;
    }

    public int judgeModelCalls() {
        return judgeModelCalls;
    }

    public int capabilityCalls() {
        return capabilityCalls;
    }

    public long stateUpdateCalls() {
        return stages.stream().filter("STATE_UPDATE"::equals).count();
    }

    /** 对照声明预算检查本次尝试,每个超支维度各产生一条违例。 */
    public List<Violation> check(CallBudget budget) {
        List<Violation> violations = new ArrayList<>();
        if (!budget.expectedStages().isEmpty()
                && !stagesEqual(budget.expectedStages(), stages)) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "expected stages " + budget.expectedStages() + " but observed " + stages));
        }
        if (productionModelCalls() < budget.minProductionCalls()
                || productionModelCalls() > budget.maxProductionCalls()) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "production calls " + productionModelCalls()
                            + " outside [" + budget.minProductionCalls()
                            + ", " + budget.maxProductionCalls() + "]"));
        }
        if (stateUpdateCalls() > budget.maxStateUpdateCalls()) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "STATE_UPDATE calls " + stateUpdateCalls()
                            + " exceed max " + budget.maxStateUpdateCalls()));
        }
        if (providerRetries > budget.maxProviderRetries()) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "provider retries " + providerRetries
                            + " exceed max " + budget.maxProviderRetries()));
        }
        if (capabilityCalls > budget.maxCapabilityCalls()) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "capability calls " + capabilityCalls
                            + " exceed max " + budget.maxCapabilityCalls()));
        }
        if (judgeModelCalls > budget.maxJudgeCalls()) {
            violations.add(new Violation(FailureClass.CALL_BUDGET,
                    "judge calls " + judgeModelCalls
                            + " exceed max " + budget.maxJudgeCalls()));
        }
        return List.copyOf(violations);
    }

    private static boolean stagesEqual(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return false;
            }
        }
        return true;
    }
}
