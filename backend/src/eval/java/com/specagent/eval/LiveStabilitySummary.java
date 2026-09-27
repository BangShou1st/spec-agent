package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文件名:LiveStabilitySummary.java
 *
 * 用途:live 评测的聚合报告,把"行为质量"与"provider 可靠性"保持为
 * 两个独立维度。{@code passed} / {@code failed} / {@code passRate} 只统计
 * 走完行为流水线的尝试;provider 或传输失败通过 {@code infrastructureFailed}
 * 与 {@code providerFailureClasses} 呈现,绝不计入行为失败。另含可用率、
 * 稳定性(重复间结果是否一致)以及 token / 延迟 / 调用开销统计。
 *
 * 协作:从 {@link ObservationEnvelope} 列表聚合,依赖
 * {@link LiveFailureClassifier} 区分基础设施失败与行为失败。
 */
public record LiveStabilitySummary(
        int plannedAttempts,
        int totalAttempts,
        int passed,
        int failed,
        double passRate,
        double layerAPassRate,
        double stability,
        int infrastructureFailed,
        double availabilityRate,
        Map<FailureClass, Integer> failureCounts,
        Map<ProviderFailureClass, Integer> providerFailureClasses,
        Map<String, Integer> primaryActionDistribution,
        Map<String, Integer> tokenTotals,
        long totalLatencyMs,
        int productionModelCalls,
        int providerRetries,
        int capabilityCalls,
        Map<String, String> scenarioResults,
        List<String> notes) {

    /** 兼容重载:观察到的尝试即计划集合。 */
    public static LiveStabilitySummary from(List<ObservationEnvelope> attempts,
                                            int repetitionsPerVariant) {
        return from(attempts, repetitionsPerVariant,
                attempts == null ? 0 : attempts.size());
    }

    /**
     * 聚合多次重复的 live 观察。计划数由测试套件提供,这样缺失的尝试
     * 在可用率报告中仍然可见。
     */
    public static LiveStabilitySummary from(List<ObservationEnvelope> attempts,
                                            int repetitionsPerVariant,
                                            int plannedAttempts) {
        List<ObservationEnvelope> safeAttempts = attempts == null ? List.of() : attempts;
        Map<FailureClass, Integer> failures = new LinkedHashMap<>();
        Map<ProviderFailureClass, Integer> providerFailures = new LinkedHashMap<>();
        Map<String, Integer> actions = new TreeMap<>();
        Map<String, String> scenarioResults = new LinkedHashMap<>();
        Map<String, List<Boolean>> perKey = new LinkedHashMap<>();
        Map<String, Integer> observedPerKey = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int passed = 0;
        int behavioralFailed = 0;
        int productionCalls = 0;
        int retries = 0;
        int capabilityCalls = 0;
        int layerAPassed = 0;
        long latencyTotal = 0;
        Map<String, Integer> tokenTotals = new LinkedHashMap<>();

        for (ObservationEnvelope attempt : safeAttempts) {
            boolean infrastructure = LiveFailureClassifier.isInfrastructureFailure(attempt);
            ProviderFailureClass providerFailure = LiveFailureClassifier.classify(attempt);
            String key = attempt.scenarioId() + "/" + attempt.variantId();
            observedPerKey.merge(key, 1, Integer::sum);
            if (infrastructure) {
                providerFailures.merge(providerFailure, 1, Integer::sum);
                scenarioResults.put(resultKey(key, attempt),
                        "INFRA:" + providerFailure);
            } else {
                if (attempt.passed()) {
                    passed++;
                    perKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(true);
                    scenarioResults.put(resultKey(key, attempt), "PASS");
                } else {
                    behavioralFailed++;
                    perKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(false);
                    scenarioResults.put(resultKey(key, attempt),
                            "FAIL:" + attempt.failureClass()
                                    + " action=" + attempt.actualPrimaryAction()
                                    + " result=" + attempt.executionResult()
                                    + " calls=" + attempt.productionModelCalls()
                                    + "+" + attempt.providerRetries() + "r");
                }
                for (Violation violation : attempt.violations()) {
                    failures.merge(violation.failureClass(), 1, Integer::sum);
                }
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

        int executed = safeAttempts.size();
        int infrastructure = providerFailures.values().stream()
                .mapToInt(Integer::intValue).sum();
        int behavioralCompleted = passed + behavioralFailed;
        double passRate = behavioralCompleted == 0
                ? 0.0 : (double) passed / behavioralCompleted;
        double layerARate = executed == 0 ? 0.0 : (double) layerAPassed / executed;
        // 只有行为流水线走完,该次尝试才算 provider 可用。运行时/领域失败
        // 仍算已执行的观察,但不降低 provider 可用率;缺失或基础设施失败的
        // 尝试才会降低。
        double availability = plannedAttempts <= 0 ? 0.0
                : (double) behavioralCompleted / plannedAttempts;

        // 稳定性只由行为结果计算。纯 provider 运行没有行为分母,
        // 因此不能被报告为"稳定"。
        long unanimous = perKey.values().stream()
                .filter(outcomes -> outcomes.stream().distinct().count() == 1)
                .count();
        double stability = perKey.isEmpty() ? 0.0
                : (double) unanimous / perKey.size();

        for (Map.Entry<String, Integer> entry : observedPerKey.entrySet()) {
            List<Boolean> outcomes = perKey.getOrDefault(entry.getKey(), List.of());
            if (outcomes.isEmpty()) {
                notes.add("NO_BEHAVIORAL_ATTEMPTS " + entry.getKey()
                        + " infrastructure_only=" + entry.getValue());
            } else if (outcomes.size() != repetitionsPerVariant) {
                notes.add("BEHAVIORAL_REPETITION_COUNT " + entry.getKey()
                        + " expected=" + repetitionsPerVariant
                        + " actual=" + outcomes.size()
                        + " observed=" + entry.getValue());
            }
            if (outcomes.stream().distinct().count() > 1) {
                notes.add("UNSTABLE " + entry.getKey() + " outcomes=" + outcomes);
            }
        }
        if (executed < plannedAttempts) {
            notes.add("PLANNED_NOT_EXECUTED expected=" + plannedAttempts
                    + " actual=" + executed);
        }

        return new LiveStabilitySummary(
                Math.max(0, plannedAttempts), executed, passed, behavioralFailed,
                passRate, layerARate, stability, infrastructure, availability,
                Map.copyOf(failures), Map.copyOf(providerFailures),
                Map.copyOf(actions), Map.copyOf(tokenTotals), latencyTotal,
                productionCalls, retries, capabilityCalls,
                Map.copyOf(scenarioResults), List.copyOf(notes));
    }

    public int behavioralCompleted() {
        return passed + failed;
    }

    public int behavioralPassed() {
        return passed;
    }

    public int behavioralFailed() {
        return failed;
    }

    public double behavioralPassRate() {
        return passRate;
    }

    private static String resultKey(String key, ObservationEnvelope attempt) {
        return key + "#" + attempt.seed();
    }

    public String toText() {
        StringBuilder rendered = new StringBuilder();
        rendered.append("live evaluation: planned=").append(plannedAttempts)
                .append(" executed=").append(totalAttempts)
                .append(" behavioral_completed=").append(behavioralCompleted())
                .append(" behavioral_passed=").append(passed)
                .append(" behavioral_failed=").append(failed)
                .append(" behavioral_pass_rate=")
                .append(String.format("%.3f", passRate)).append("\n");
        rendered.append("infrastructure_failed=").append(infrastructureFailed)
                .append(" availability_rate=")
                .append(String.format("%.3f", availabilityRate)).append("\n");
        rendered.append("layer_a_pass_rate=").append(String.format("%.3f", layerAPassRate)).append("\n");
        rendered.append("behavioral_stability=").append(String.format("%.3f", stability)).append("\n");
        rendered.append("behavioral_failures=").append(failureCounts).append("\n");
        rendered.append("provider_failure_classes=").append(providerFailureClasses).append("\n");
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
