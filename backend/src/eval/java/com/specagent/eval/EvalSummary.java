package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregate of one eval run over many attempt observations. */
public record EvalSummary(
        int totalAttempts,
        int passed,
        int failed,
        double layerAPassRate,
        double layerBFastPassRate,
        Map<FailureClass, Integer> failureCounts,
        Map<String, Integer> primaryActionDistribution,
        List<String> unexpectedStateDeltas,
        int callBudgetViolations,
        int productionModelCalls,
        int providerRetries,
        int judgeModelCalls,
        int capabilityCalls,
        Map<String, String> scenarioResults) {

    public static EvalSummary from(List<ObservationEnvelope> attempts) {
        Map<FailureClass, Integer> failures = new LinkedHashMap<>();
        Map<String, Integer> actions = new TreeMap<>();
        List<String> unexpectedDeltas = new ArrayList<>();
        Map<String, String> scenarioResults = new LinkedHashMap<>();
        int passed = 0;
        int budgetViolations = 0;
        int productionCalls = 0;
        int retries = 0;
        int judgeCalls = 0;
        int capabilityCalls = 0;
        int layerAPassed = 0;
        int layerBPassed = 0;

        for (ObservationEnvelope attempt : attempts) {
            if (attempt.passed()) {
                passed++;
            }
            scenarioResults.put(attempt.scenarioId() + "/" + attempt.variantId(),
                    attempt.passed() ? "PASS" : "FAIL:" + attempt.failureClass());
            for (Violation violation : attempt.violations()) {
                failures.merge(violation.failureClass(), 1, Integer::sum);
                if (violation.failureClass() == FailureClass.CALL_BUDGET) {
                    budgetViolations++;
                }
                if (violation.failureClass() == FailureClass.UNEXPECTED_STATE_DELTA
                        || violation.failureClass() == FailureClass.RUNTIME_INVARIANT) {
                    unexpectedDeltas.add(attempt.scenarioId() + "/" + attempt.variantId()
                            + ": " + violation.detail());
                }
            }
            if (attempt.actualPrimaryAction() != null) {
                actions.merge(attempt.actualPrimaryAction(), 1, Integer::sum);
            }
            productionCalls += attempt.productionModelCalls();
            retries += attempt.providerRetries();
            judgeCalls += attempt.judgeModelCalls();
            capabilityCalls += attempt.capabilityCalls();
            if (attempt.invariantResults().stream().allMatch(CheckResult::passed)) {
                layerAPassed++;
            }
            if (attempt.propertyResults().stream().allMatch(CheckResult::passed)) {
                layerBPassed++;
            }
        }

        int total = attempts.size();
        return new EvalSummary(
                total, passed, total - passed,
                total == 0 ? 1.0 : (double) layerAPassed / total,
                total == 0 ? 1.0 : (double) layerBPassed / total,
                Map.copyOf(failures), Map.copyOf(actions),
                List.copyOf(unexpectedDeltas), budgetViolations,
                productionCalls, retries, judgeCalls, capabilityCalls,
                Map.copyOf(scenarioResults));
    }

    public String toText() {
        StringBuilder rendered = new StringBuilder();
        rendered.append("eval summary: total=").append(totalAttempts)
                .append(" passed=").append(passed)
                .append(" failed=").append(failed).append("\n");
        rendered.append("layer_a_pass_rate=").append(String.format("%.3f", layerAPassRate)).append("\n");
        rendered.append("layer_b_fast_pass_rate=").append(String.format("%.3f", layerBFastPassRate)).append("\n");
        rendered.append("failures=").append(failureCounts).append("\n");
        rendered.append("actions=").append(primaryActionDistribution).append("\n");
        rendered.append("call_budget_violations=").append(callBudgetViolations).append("\n");
        rendered.append("production_model_calls=").append(productionModelCalls)
                .append(" provider_retries=").append(providerRetries)
                .append(" judge_calls=").append(judgeModelCalls)
                .append(" capability_calls=").append(capabilityCalls).append("\n");
        scenarioResults.forEach((key, value) ->
                rendered.append(key).append(" -> ").append(value).append("\n"));
        return rendered.toString();
    }
}
