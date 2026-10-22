package com.specagent.retrieval.eval;

import java.util.*;

/**
 * 纯检索质量评分器 (Pure Quality Scorer)。
 *
 * 依据冻结且获得批准的质量契约，对实际检索 Top-8 结果进行纯函数评分与通过判定。
 * 不产生副作用，不依赖外部服务或数据库。
 *
 * 指标与分类规则（Sol F05/F19）：
 * 1. 严格保留 Top-8 原始排名：同源多个 chunk 占前七位、目标排第八位时按 1/8 (0.125) 计，不被外部去重压缩。
 * 2. 正向查询（分母固化 49 条）：
 *    - Source Recall@8：按真实来源 alias 去重后统计召回数除以 expected 长度。
 *    - MRR@8：保留原始排名的首个相关来源倒数排名。
 *    - Multi-Source All-Of Coverage：针对 MULTI_SOURCE（8条），必须覆盖全部必需证据组。
 * 3. 负例与诊断查询：
 *    - NEAR_NO_ANSWER（8条）：作为诊断观察项，语料不充分但存在相关背景，返回合法公开背景不判质量 FAIL；但禁止命中 forbidden。
 *    - NO_ANSWER（3条纯无答案）：统计严格零命中率，返回词法噪声不计入充分证据，禁止命中 forbidden。
 *    - STALE_VERSION（4条）与 PERMISSION_ISOLATION（4条）：硬安全门禁，命中对应 stale/forbidden 来源立即判 FAIL。
 * 4. 审批有效性：未批准或未知阈值状态直接判 BLOCKED_UNAPPROVED_THRESHOLDS，不予通过。
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
            double minPureNoAnswerZeroHitCorrectness, // 仅针对纯负例，必需指标字段，不可为 null
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
            int positiveQueriesCount,
            int multiSourceQueriesCount,
            int nearNoAnswerCount,
            int pureNoAnswerCount,
            double macroSourceRecallAt8,
            double firstRelevantMrr,
            double multiSourceAllOfCoverage,
            double pureNoAnswerZeroHitRate,
            int nearNoAnswerBackgroundReturnedCount,
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
        int positiveCount = 0;
        int multiSourceCount = 0;
        int nearCount = 0;
        int pureNoAnswerCount = 0;

        double sumRecall = 0.0;
        double sumMrr = 0.0;
        int multiSourceAllOfHits = 0;

        int pureNoAnswerZeroHits = 0;
        int nearBackgroundReturned = 0;

        int staleLeaks = 0;
        int permissionLeaks = 0;

        for (QueryObservation obs : observations) {
            List<String> expected = obs.expectedAliases();
            List<String> forbidden = obs.forbiddenAliases();
            List<String> retrieved = obs.retrievedAliases();
            String cat = obs.category() == null ? "" : obs.category();

            // 1. 全局安全硬门禁检查：所有查询返回的任何来源均不得命中 forbidden 或 stale
            for (String alias : retrieved) {
                if (forbidden.contains(alias)) {
                    permissionLeaks++;
                }
                if (staleSet.contains(alias)) {
                    staleLeaks++;
                }
            }

            // 2. 正向查询评估（expected 非空）
            if (!expected.isEmpty()) {
                positiveCount++;

                // Source Recall@8: 按唯一来源 alias 去重统计命中数
                Set<String> distinctRetrieved = new HashSet<>(retrieved);
                long hits = expected.stream().filter(distinctRetrieved::contains).count();
                double queryRecall = (double) hits / expected.size();
                sumRecall += queryRecall;

                // MRR@8: 保留原始排名位置（1-8），不因去重压缩 index
                double queryMrr = 0.0;
                for (int rank = 0; rank < retrieved.size(); rank++) {
                    String actualAlias = retrieved.get(rank);
                    if (expected.contains(actualAlias)) {
                        queryMrr = 1.0 / (rank + 1);
                        break;
                    }
                }
                sumMrr += queryMrr;

                // Multi-Source All-Of Coverage: 必须覆盖全部必需证据组
                if ("MULTI_SOURCE".equals(cat) || expected.size() >= 2) {
                    multiSourceCount++;
                    if (distinctRetrieved.containsAll(expected)) {
                        multiSourceAllOfHits++;
                    }
                }
            } else {
                // 3. 负例与诊断查询细分处理
                if ("NEAR_NO_ANSWER".equals(cat)) {
                    nearCount++;
                    if (!retrieved.isEmpty()) {
                        // 返回了合法公开背景/相关节点，仅做诊断计数，不判零命中失败
                        nearBackgroundReturned++;
                    }
                } else if ("NO_ANSWER".equals(cat)) {
                    pureNoAnswerCount++;
                    if (retrieved.isEmpty()) {
                        pureNoAnswerZeroHits++;
                    }
                }
                // STALE_VERSION 与 PERMISSION_ISOLATION 归属于全局硬安全检查，不强制要求 retrieved 为空
            }
        }

        double macroRecall = positiveCount == 0 ? 0.0 : sumRecall / positiveCount;
        double meanMrr = positiveCount == 0 ? 0.0 : sumMrr / positiveCount;
        double allOfCoverage = multiSourceCount == 0 ? 1.0 : (double) multiSourceAllOfHits / multiSourceCount;
        double pureZeroHitRate = pureNoAnswerCount == 0 ? 1.0 : (double) pureNoAnswerZeroHits / pureNoAnswerCount;

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
        if (multiSourceCount > 0 && allOfCoverage < thresholds.minMultiSourceAllOfCoverage()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Multi-Source All-Of Coverage failed: actual %.4f < required %.4f",
                    allOfCoverage, thresholds.minMultiSourceAllOfCoverage()));
        }
        if (pureNoAnswerCount > 0 && pureZeroHitRate < thresholds.minPureNoAnswerZeroHitCorrectness()) {
            failureReasons.add(String.format(Locale.ROOT,
                    "Pure No-Answer Zero-Hit Correctness failed: actual %.4f < required %.4f",
                    pureZeroHitRate, thresholds.minPureNoAnswerZeroHitCorrectness()));
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
        summary.put("positiveQueriesCount", positiveCount);
        summary.put("multiSourceQueriesCount", multiSourceCount);
        summary.put("nearNoAnswerCount", nearCount);
        summary.put("pureNoAnswerCount", pureNoAnswerCount);
        summary.put("macroSourceRecallAt8", macroRecall);
        summary.put("firstRelevantMrr", meanMrr);
        summary.put("multiSourceAllOfCoverage", allOfCoverage);
        summary.put("pureNoAnswerZeroHitRate", pureZeroHitRate);
        summary.put("nearNoAnswerBackgroundReturnedCount", nearBackgroundReturned);
        summary.put("staleSourceLeakCount", staleLeaks);
        summary.put("permissionLeakCount", permissionLeaks);

        return new QualityReport(
                verdict,
                totalQueries,
                positiveCount,
                multiSourceCount,
                nearCount,
                pureNoAnswerCount,
                macroRecall,
                meanMrr,
                allOfCoverage,
                pureZeroHitRate,
                nearBackgroundReturned,
                staleLeaks,
                permissionLeaks,
                List.copyOf(failureReasons),
                summary
        );
    }
}
