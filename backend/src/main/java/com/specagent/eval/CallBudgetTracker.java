package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent accounting of production reasoning calls, provider retries,
 * judge calls, and capability calls for one attempt.
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

    /** Rehydrates counts without stage detail (artifact replay). */
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

    /** Checks this attempt against the declared budget, one violation per breached dimension. */
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
