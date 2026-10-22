package com.specagent.retrieval.eval;

import java.util.*;

/**
 * 纯检索质量评分器 (Pure Quality Scorer)。
 *
 * 依据冻结且获得批准的质量契约，对实际检索 Top-8 结果进行纯函数评分与通过判定。
 * 不产生副作用，不依赖外部服务或数据库。
 *
 * 核心指标与规则：
 * 1. Source Recall@8：按真实来源 alias 去重后统计召回数，除以必需证据集合大小。背景来源不计入充分证据命中。
 * 2. MRR@8：严格保留检索原始排名（1-8），不因来源去重而压缩位置（同源多个 chunk 占前七位、目标排第八位时按 1/8 计）。
 * 3. Multi-Source All-Of Coverage：多来源查询必须全部覆盖所有必需证据（All-Of），只命中部分证据算 0 分。
 * 4. Zero-Hit / No-Answer：统计无答案/负例查询的零命中率，并防范词法噪声。
 * 5. 安全硬门槛：Stale 撤回来源泄漏数与跨项目/未授权 Permission 泄漏数必须为 0，否则直接判 FAIL。
 * 6. 分母固化：必须按全部候选分母统计，禁止因空结果或异常过滤分母。
 * 7. 审批有效性：未批准或未知阈值状态直接判 BLOCKED_UNAPPROVED_THRESHOLDS，不予通过。
 */
public final class RetrievalQualityScorer {

    public record QueryObservation(
            String queryId,
            String category,
            List<String> expectedAliases,
            List<String> forbiddenAliases,
            List<String> backgroundAliases,
            List<String> retrievedAliases, // Top-8 原始顺序，可能含同源多个 chunk
            boolean vectorUnavailable,
            long elapsedMs
    ) {
        public QueryObservation {
            expectedAliases = expectedAliases == null ? List.of() : List.copyOf(expectedAliases);
            forbiddenAliases = forbiddenAliases == null ? List.of() : List.copyOf(forbiddenAliases);
            backgroundAliases = backgroundAliases == null ? List.of() : List.copyOf(backgroundAliases);
            retrievedAliases = retrievedAliases == null ? List.of() : List.copyOf(retrievedAliases);
        }
    }

    public record QualityThresholds(
            String thresholdStatus,
            double minMacroSourceRecallAt8,
            double minFirstRelevantMrr,
            double minMultiSourceAllOfCoverage,
            Double minNoAnswerZeroHitCorrectness,
            int maxStaleSourceLeaks,
            int maxPermissionLeaks
    ) {
        public boolean isApproved() {
            if (thresholdStatus == null || thresholdStatus.isBlank()) return false;
            String upper = thresholdStatus.trim().toUpperCase(Locale.ROOT);
            if (upper.contains("PROPOSED") || upper.contains("PENDING") || upper.contains("NOT_APPROVED")) {
                return false;
            }
            return upper.equals("APPROVED_BEFORE_FIRST_RUN") || upper.equals("APPROVED");
        }
    }

    public record QualityReport(
            String verdict, // "PASS", "FAIL", "BLOCKED_UNAPPROVED_THRESHOLDS"
            int totalQueries,
            int labelledQueriesCount,
            int multiSourceQueriesCount,
            int negativeQueriesCount,
            double macroSourceRecallAt8,
            double firstRelevantMrr,
            double multiSourceAllOfCoverage,
            double noAnswerZeroHitCorrectness,
            int staleSourceLeakCount,
            int permissionLeakCount,
            List<String> failureReasons,
            Map<String, Object> summary
    ) {
        public boolean isPass() {
            return "PASS".equals(verdict);
        }
    }

    public static QualityReport evaluate(
            List<QueryObservation> observations,
            QualityThresholds thresholds,
            Set<String> staleAliases
    ) {
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        Set<String> staleSet = staleAliases == null ? Set.of() : staleAliases;

        List<String> failureReasons = new ArrayList<>();

        if (!thresholds.isApproved()) {
            failureReasons.add("Threshold contract status is not approved: '" + thresholds.thresholdStatus() + "'");
        }

        int totalQueries = observations.size();
        int labelledCount = 0;
        int multiSourceCount = 0;
        int negativeCount = 0;

        double sumRecall = 0.0;
        double sumMrr = 0.0;
        int multiSourceAllOfHits = 0;
        int zeroHitCount = 0;

        int staleLeaks = 0;
        int permissionLeaks = 0;

        for (QueryObservation obs : observations) {
            List<String> expected = obs.expectedAliases();
            List<String> forbidden = obs.forbiddenAliases();
            List<String> retrieved = obs.retrievedAliases();

            // 安全检查：检查所有返回项中是否有 stale 或 forbidden 来源
            for (String alias : retrieved) {
                if (forbidden.contains(alias)) {
                    permissionLeaks++;
                }
                if (staleSet.contains(alias)) {
                    staleLeaks++;
                }
            }

            boolean isLabelled = !expected.isEmpty();
            if (isLabelled) {
                labelledCount++;

                // 1. Source Recall@8: 按唯一来源 alias 去重统计命中数
                Set<String> distinctRetrieved = new HashSet<>(retrieved);
                long hits = expected.stream().filter(distinctRetrieved::contains).count();
                double queryRecall = (double) hits / expected.size();
                sumRecall += queryRecall;

                // 2. MRR@8: 保留原始排名位置，不因去重压缩 index
                double queryMrr = 0.0;
                for (int rank = 0; rank < retrieved.size(); rank++) {
                    String actualAlias = retrieved.get(rank);
                    if (expected.contains(actualAlias)) {
                        queryMrr = 1.0 / (rank + 1);
                        break;
                    }
                }
                sumMrr += queryMrr;

                // 3. Multi-Source All-Of: 必须覆盖全部必需证据组
                if ("MULTI_SOURCE".equals(obs.category()) || expected.size() >= 2) {
                    multiSourceCount++;
                    if (distinctRetrieved.containsAll(expected)) {
                        multiSourceAllOfHits++;
                    }
                }
            } else {
                negativeCount++;
                if (retrieved.isEmpty()) {
                    zeroHitCount++;
                }
            }
        }

        double macroRecall = labelledCount == 0 ? 0.0 : sumRecall / labelledCount;
        double meanMrr = labelledCount == 0 ? 0.0 : sumMrr / labelledCount;
        double allOfCoverage = multiSourceCount == 0 ? 0.0 : (double) multiSourceAllOfHits / multiSourceCount;
        double zeroHitRate = negativeCount == 0 ? 1.0 : (double) zeroHitCount / negativeCount;

        // 阈值断言
        if (macroRecall < thresholds.minMacroSourceRecallAt8()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Macro Source Recall@8 failed: actual %.4f < required %.4f",
                    macroRecall, thresholds.minMacroSourceRecallAt8()));
        }
        if (meanMrr < thresholds.minFirstRelevantMrr()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "MRR@8 failed: actual %.4f < required %.4f",
                    meanMrr, thresholds.minFirstRelevantMrr()));
        }
        if (allOfCoverage < thresholds.minMultiSourceAllOfCoverage()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Multi-Source All-Of Coverage failed: actual %.4f < required %.4f",
                    allOfCoverage, thresholds.minMultiSourceAllOfCoverage()));
        }
        if (thresholds.minNoAnswerZeroHitCorrectness() != null) {
            if (zeroHitRate < thresholds.minNoAnswerZeroHitCorrectness()) {
                failureReasons.add(String.format(Locale.ROOT,
                        "Zero-Hit Correctness failed: actual %.4f < required %.4f",
                        zeroHitRate, thresholds.minNoAnswerZeroHitCorrectness()));
            }
        }
        if (staleLeaks > thresholds.maxStaleSourceLeaks()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Stale source leaks failed: actual %d > allowed %d",
                    staleLeaks, thresholds.maxStaleSourceLeaks()));
        }
        if (permissionLeaks > thresholds.maxPermissionLeaks()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Permission leaks failed: actual %d > allowed %d",
                    permissionLeaks, thresholds.maxPermissionLeaks()));
        }

        String verdict;
        if (!thresholds.isApproved()) {
            verdict = "BLOCKED_UNAPPROVED_THRESHOLDS";
        } else if (failureReasons.isEmpty()) {
            verdict = "PASS";
        } else {
            verdict = "FAIL";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", verdict);
        summary.put("totalQueries", totalQueries);
        summary.put("labelledQueriesCount", labelledCount);
        summary.put("multiSourceQueriesCount", multiSourceCount);
        summary.put("negativeQueriesCount", negativeCount);
        summary.put("macroSourceRecallAt8", macroRecall);
        summary.put("firstRelevantMrr", meanMrr);
        summary.put("multiSourceAllOfCoverage", allOfCoverage);
        summary.put("noAnswerZeroHitCorrectness", zeroHitRate);
        summary.put("staleSourceLeakCount", staleLeaks);
        summary.put("permissionLeakCount", permissionLeaks);

        return new QualityReport(
                verdict,
                totalQueries,
                labelledCount,
                multiSourceCount,
                negativeCount,
                macroRecall,
                meanMrr,
                allOfCoverage,
                zeroHitRate,
                staleLeaks,
                permissionLeaks,
                List.copyOf(failureReasons),
                summary
        );
    }
}
