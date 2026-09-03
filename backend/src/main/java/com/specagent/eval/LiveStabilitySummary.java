package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P2 Phase 2 — Live behavioral baseline aggregation over repeated attempts.
 *
 * <p>Unlike {@link EvalSummary} (one attempt per variant, pass/fail frozen),
 * the live baseline records N repeated attempts per scenario variant and
 * reports stability: how often the same Scenario Contract passes, which
 * primary actions appear across repetitions, and which failure classes recur.
 * Every attempt is still judged by the identical Scenario Contract (Layer A
 * invariants + Layer B expectations + call budget) — repetition never relaxes
 * the contract.
 *
 * <p>Reporting only: token/cost/latency fields stay {@code unknown}/null
 * unless the runtime genuinely provides them.
 */
public record LiveStabilitySummary(
        int totalAttempts,
        int passed,
        int failed,
        double passRate,
        double layerAPassRate,
        double stability,
        Map<FailureClass, Integer> failureCounts,
        Map<String, Integer> primaryActionDistribution,
        Map<String, Integer> tokenTotals,
        long totalLatencyMs,
        int productionModelCalls,
        int providerRetries,
        int capabilityCalls,
        Map<String, String> scenarioResults,
        List<String> notes) {

    /**
     * Aggregates live attempts keyed per scenario/variant/repetition.
     *
     * @param attempts every repeated observation (same scenarioIds may repeat)
     * @param repetitionsPerVariant declared N (used only for the stability
     *     denominator note; never inferred per key)
     */
    public static LiveStabilitySummary from(List<ObservationEnvelope> attempts,
                                            int repetitionsPerVariant) {
        Map<FailureClass, Integer> failures = new LinkedHashMap<>();
        Map<String, Integer> actions = new TreeMap<>();
        Map<String, String> scenarioResults = new LinkedHashMap<>();
        Map<String, List<Boolean>> perKey = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int passed = 0;
        int productionCalls = 0;
        int retries = 0;
        int capabilityCalls = 0;
        int layerAPassed = 0;
        long latencyTotal = 0;
        Map<String, Integer> tokenTotals = new LinkedHashMap<>();

        for (ObservationEnvelope attempt : attempts) {
            if (attempt.passed()) {
                passed++;
            }
            String key = attempt.scenarioId() + "/" + attempt.variantId();
            perKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(attempt.passed());
            String repKey = key + "#" + attempt.seed();
            scenarioResults.put(repKey, attempt.passed() ? "PASS"
                    : "FAIL:" + attempt.failureClass()
                    + " action=" + attempt.actualPrimaryAction()
                    + " result=" + attempt.executionResult()
                    + " calls=" + attempt.productionModelCalls()
                    + "+" + attempt.providerRetries() + "r");
            for (Violation violation : attempt.violations()) {
                failures.merge(violation.failureClass(), 1, Integer::sum);
            }
            if (attempt.actualPrimaryAction() != null) {
                actions.merge(attempt.actualPrimaryAction(), 1, Integer::sum);
            }
            if (attempt.inputTokens() != null) {
                tokenTotals.merge("input", attempt.inputTokens().intValue(), Integer::sum);
            }
            if (attempt.outputTokens() != null) {
                tokenTotals.merge("output", attempt.outputTokens().intValue(), Integer::sum);
            }
            if (attempt.latencyMs() != null) {
                latencyTotal += attempt.latencyMs();
            }
            productionCalls += attempt.productionModelCalls();
            retries += attempt.providerRetries();
            capabilityCalls += attempt.capabilityCalls();
            if (attempt.invariantResults().stream().allMatch(CheckResult::passed)) {
                layerAPassed++;
            }
        }

        int total = attempts.size();
        double passRate = total == 0 ? 1.0 : (double) passed / total;
        double layerARate = total == 0 ? 1.0 : (double) layerAPassed / total;
        // Stability: fraction of scenario/variant keys whose N repetitions
        // agree unanimously (all pass or all fail). A key with mixed outcomes
        // is unstable regardless of direction — that is the live signal.
        long unanimous = perKey.values().stream()
                .filter(outcomes -> outcomes.stream().distinct().count() == 1)
                .count();
        double stability = perKey.isEmpty() ? 1.0 : (double) unanimous / perKey.size();

        for (Map.Entry<String, List<Boolean>> entry : perKey.entrySet()) {
            List<Boolean> outcomes = entry.getValue();
            if (outcomes.stream().distinct().count() > 1) {
                notes.add("UNSTABLE " + entry.getKey() + " outcomes=" + outcomes);
            }
            if (outcomes.size() != repetitionsPerVariant) {
                notes.add("REPETITION_COUNT " + entry.getKey()
                        + " expected=" + repetitionsPerVariant
                        + " actual=" + outcomes.size());
            }
        }

        return new LiveStabilitySummary(
                total, passed, total - passed, passRate, layerARate, stability,
                Map.copyOf(failures), Map.copyOf(actions), Map.copyOf(tokenTotals),
                latencyTotal, productionCalls, retries, capabilityCalls,
                Map.copyOf(scenarioResults), List.copyOf(notes));
    }

    public String toText() {
        StringBuilder rendered = new StringBuilder();
        rendered.append("live baseline: total=").append(totalAttempts)
                .append(" passed=").append(passed)
                .append(" failed=").append(failed)
                .append(" pass_rate=").append(String.format("%.3f", passRate)).append("\n");
        rendered.append("layer_a_pass_rate=").append(String.format("%.3f", layerAPassRate)).append("\n");
        rendered.append("stability=").append(String.format("%.3f", stability)).append("\n");
        rendered.append("failures=").append(failureCounts).append("\n");
        rendered.append("actions=").append(primaryActionDistribution).append("\n");
        rendered.append("tokens=").append(tokenTotals)
                .append(" total_latency_ms=").append(totalLatencyMs).append("\n");
        rendered.append("production_model_calls=").append(productionModelCalls)
                .append(" provider_retries=").append(providerRetries)
                .append(" capability_calls=").append(capabilityCalls).append("\n");
        scenarioResults.forEach((key, value) ->
                rendered.append(key).append(" -> ").append(value).append("\n"));
        notes.forEach(note -> rendered.append(note).append("\n"));
        return rendered.toString();
    }
}
