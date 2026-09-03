package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Overall result of one scenario attempt with its typed violations. */
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
