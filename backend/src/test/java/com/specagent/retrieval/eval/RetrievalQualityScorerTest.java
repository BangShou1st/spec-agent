package com.specagent.retrieval.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQualityScorerTest {

    private static final RetrievalQualityScorer.QualityThresholds APPROVED_THRESHOLDS =
            new RetrievalQualityScorer.QualityThresholds(
                    "APPROVED_BEFORE_FIRST_RUN",
                    0.85,  // Recall@8 >= 0.85
                    0.75,  // MRR@8 >= 0.75
                    0.75,  // Multi-source All-Of >= 0.75
                    1.0,   // Zero-hit = 1.0 (strict)
                    0,     // Stale leaks = 0
                    0      // Permission leaks = 0
            );

    @Test
    @DisplayName("48条正向查询全部无命中，必须判 FAIL 且 Recall=0, MRR=0")
    void allFortyEightZeroHitsFailsQuality() {
        List<RetrievalQualityScorer.QueryObservation> observations = new ArrayList<>();
        for (int i = 1; i <= 48; i++) {
            observations.add(new RetrievalQualityScorer.QueryObservation(
                    "B" + String.format("%03d", i),
                    "SINGLE_SOURCE_NODE",
                    List.of("N01"),
                    List.of("S00", "P00"),
                    List.of(),
                    List.of(), // 完全无结果
                    false,
                    10L
            ));
        }

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of("S00"));
        assertThat(report.isPass()).isFalse();
        assertThat(report.verdict()).isEqualTo("FAIL");
        assertThat(report.macroSourceRecallAt8()).isEqualTo(0.0);
        assertThat(report.firstRelevantMrr()).isEqualTo(0.0);
        assertThat(report.failureReasons())
                .anyMatch(r -> r.contains("Recall@8 failed"))
                .anyMatch(r -> r.contains("MRR@8 failed"));
    }

    @Test
    @DisplayName("Recall 低于门槛 (0.84 < 0.85) 必须判 FAIL")
    void recallBelowThresholdFailsQuality() {
        List<RetrievalQualityScorer.QueryObservation> observations = new ArrayList<>();
        // 100 条查询，84 条命中，16 条未命中 -> recall = 0.84
        for (int i = 0; i < 100; i++) {
            List<String> retrieved = (i < 84) ? List.of("N01") : List.of();
            observations.add(new RetrievalQualityScorer.QueryObservation(
                    "Q" + i, "SINGLE_SOURCE_NODE", List.of("N01"), List.of(), List.of(), retrieved, false, 5L
            ));
        }

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.verdict()).isEqualTo("FAIL");
        assertThat(report.macroSourceRecallAt8()).isEqualTo(0.84);
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Macro Source Recall@8 failed: actual 0.8400 < required 0.8500"));
    }

    @Test
    @DisplayName("MRR 低于门槛 (0.70 < 0.75) 必须判 FAIL")
    void mrrBelowThresholdFailsQuality() {
        List<RetrievalQualityScorer.QueryObservation> observations = new ArrayList<>();
        // 所有查询均召回，但在 rank 2 (MRR = 0.50)
        for (int i = 0; i < 10; i++) {
            observations.add(new RetrievalQualityScorer.QueryObservation(
                    "Q" + i, "SINGLE_SOURCE_NODE", List.of("N01"), List.of(), List.of(),
                    List.of("OTHER_SOURCE", "N01"), false, 5L
            ));
        }

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.verdict()).isEqualTo("FAIL");
        assertThat(report.firstRelevantMrr()).isEqualTo(0.50);
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("MRR@8 failed: actual 0.5000 < required 0.7500"));
    }

    @Test
    @DisplayName("多来源查询只命中其中一个，All-Of 覆盖率必须判定为 0.0 并判 FAIL")
    void multiSourcePartialHitFailsAllOfCoverage() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B041", "MULTI_SOURCE", List.of("N00", "N01"), List.of(), List.of(),
                        List.of("N00"), // 只命中了 N00，缺少 N01
                        false, 15L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.verdict()).isEqualTo("FAIL");
        assertThat(report.multiSourceAllOfCoverage()).isEqualTo(0.0);
        assertThat(report.macroSourceRecallAt8()).isEqualTo(0.5); // 单项 recall 1/2 = 0.5
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Multi-Source All-Of Coverage failed"));
    }

    @Test
    @DisplayName("合法背景命中但不包含充分证据，不能算作充分命中，Recall 必须为 0")
    void backgroundSourceDoesNotCountTowardsExpected() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B010", "SINGLE_SOURCE_NODE", List.of("N03"), List.of(), List.of("N03_BG"),
                        List.of("N03_BG"), // 命中了背景，但期望必需证据是 N03
                        false, 10L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.macroSourceRecallAt8()).isEqualTo(0.0);
        assertThat(report.firstRelevantMrr()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("近主题无答案 (NEAR_NO_ANSWER) 返回合法背景作为诊断项，不因严格零命中判 FAIL")
    void nearNoAnswerReturningLegalBackgroundDoesNotFailQuality() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B001", "SINGLE_SOURCE_NODE", List.of("N01"), List.of("P00"), List.of(),
                        List.of("N01"), false, 10L
                ),
                new RetrievalQualityScorer.QueryObservation(
                        "B050", "NEAR_NO_ANSWER", List.of(), List.of("P00"), List.of("N03_BG"),
                        List.of("N03_BG"), // 合法背景，作为诊断项
                        false, 10L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of("S00"));
        assertThat(report.isPass()).isTrue();
        assertThat(report.verdict()).isEqualTo("PASS");
        assertThat(report.permissionLeakCount()).isEqualTo(0);
        assertThat(report.nearNoAnswerBackgroundReturnedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("近主题无答案 (NEAR_NO_ANSWER) 若命中 Forbidden 来源，仍必须直接判 FAIL")
    void nearNoAnswerReturningForbiddenFailsImmediately() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B050", "NEAR_NO_ANSWER", List.of(), List.of("P00"), List.of(),
                        List.of("P00"), // 泄露了 forbidden
                        false, 10L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.permissionLeakCount()).isEqualTo(1);
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Permission leaks failed"));
    }

    @Test
    @DisplayName("同源多 chunk 占据前七位，目标排第八位，MRR 严格保留原始排名 1/8=0.125")
    void duplicateChunksPreserveOriginalRankingForMrr() {
        // 前 7 个 chunk 均属于 N99，目标 N01 排在第 8 位
        List<String> retrieved = List.of("N99", "N99", "N99", "N99", "N99", "N99", "N99", "N01");
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B001", "SINGLE_SOURCE_NODE", List.of("N01"), List.of(), List.of(),
                        retrieved, false, 20L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        // Rank 8 -> MRR = 1/8 = 0.125
        assertThat(report.firstRelevantMrr()).isEqualTo(1.0 / 8.0);
        // Source recall: N01 命中，recall = 1.0
        assertThat(report.macroSourceRecallAt8()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("命中了过期 Stale 来源，必须判 FAIL")
    void staleSourceLeakFailsImmediately() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B057", "STALE_REVOCATION", List.of(), List.of(), List.of(),
                        List.of("S00"), false, 12L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of("S00", "S01"));
        assertThat(report.isPass()).isFalse();
        assertThat(report.staleSourceLeakCount()).isEqualTo(1);
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Stale source leaks failed: actual 1 > allowed 0"));
    }

    @Test
    @DisplayName("命中了跨项目/未授权 Forbidden 来源，必须判 FAIL")
    void crossProjectPermissionLeakFailsImmediately() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B061", "PRIVATE_TENANT", List.of(), List.of("P00"), List.of(),
                        List.of("P00"), false, 12L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.permissionLeakCount()).isEqualTo(1);
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Permission leaks failed: actual 1 > allowed 0"));
    }

    @Test
    @DisplayName("无答案查询返回合法但不充分的词法噪声：严格零命中率降低，若要求1.0则判FAIL")
    void noAnswerQueryWithLexicalNoise() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B065", "NO_ANSWER", List.of(), List.of("P00"), List.of(),
                        List.of("N07"), // 词法噪声匹配到了合法公开源 N07，但无答案
                        false, 10L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.pureNoAnswerZeroHitRate()).isEqualTo(0.0);
        assertThat(report.permissionLeakCount()).isEqualTo(0); // 非 forbidden，无安全泄露
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Zero-Hit Correctness failed"));
    }

    @Test
    @DisplayName("阈值未获得人工批准（PROPOSED_BASELINE 或 PENDING），必须被阻断判定为 BLOCKED_UNAPPROVED_THRESHOLDS")
    void unapprovedThresholdStatusIsBlocked() {
        RetrievalQualityScorer.QualityThresholds unapproved = new RetrievalQualityScorer.QualityThresholds(
                "PROPOSED_BASELINE; awaiting human business approval",
                0.85, 0.75, 0.75, 1.0, 0, 0
        );

        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B001", "SINGLE_SOURCE_NODE", List.of("N01"), List.of(), List.of(),
                        List.of("N01"), false, 10L
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, unapproved, Set.of());
        assertThat(report.isPass()).isFalse();
        assertThat(report.verdict()).isEqualTo("BLOCKED_UNAPPROVED_THRESHOLDS");
        assertThat(report.failureReasons()).anyMatch(r -> r.contains("Threshold contract status is not approved"));
    }

    @Test
    @DisplayName("全部指标达标且阈值获得批准，判定为 PASS")
    void approvedThresholdsWithAllPassingMetricsYieldsPass() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B001", "SINGLE_SOURCE_NODE", List.of("N01"), List.of("P00"), List.of(),
                        List.of("N01"), false, 10L
                ),
                new RetrievalQualityScorer.QueryObservation(
                        "B041", "MULTI_SOURCE", List.of("N00", "N01"), List.of("P00"), List.of(),
                        List.of("N00", "N01"), false, 12L
                ),
                new RetrievalQualityScorer.QueryObservation(
                        "B065", "NO_ANSWER", List.of(), List.of("P00"), List.of(),
                        List.of(), false, 8L // 严格零命中
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of("S00"));
        assertThat(report.isPass()).isTrue();
        assertThat(report.verdict()).isEqualTo("PASS");
        assertThat(report.macroSourceRecallAt8()).isEqualTo(1.0);
        assertThat(report.firstRelevantMrr()).isEqualTo(1.0);
        assertThat(report.multiSourceAllOfCoverage()).isEqualTo(1.0);
        assertThat(report.pureNoAnswerZeroHitRate()).isEqualTo(1.0);
        assertThat(report.staleSourceLeakCount()).isEqualTo(0);
        assertThat(report.permissionLeakCount()).isEqualTo(0);
        assertThat(report.failureReasons()).isEmpty();
    }

    @Test
    @DisplayName("分母固化：空结果或失败的查询绝不从分母中排除")
    void denominatorIsNotReducedWhenResultsEmpty() {
        List<RetrievalQualityScorer.QueryObservation> observations = List.of(
                new RetrievalQualityScorer.QueryObservation(
                        "B001", "SINGLE_SOURCE_NODE", List.of("N01"), List.of(), List.of(),
                        List.of("N01"), false, 10L // recall = 1.0
                ),
                new RetrievalQualityScorer.QueryObservation(
                        "B002", "SINGLE_SOURCE_NODE", List.of("N02"), List.of(), List.of(),
                        List.of(), false, 10L // recall = 0.0
                )
        );

        var report = RetrievalQualityScorer.evaluate(observations, APPROVED_THRESHOLDS, Set.of());
        // 分母为 2，sumRecall = 1.0 -> macroRecall = 0.50
        assertThat(report.positiveQueriesCount()).isEqualTo(2);
        assertThat(report.macroSourceRecallAt8()).isEqualTo(0.50);
        assertThat(report.firstRelevantMrr()).isEqualTo(0.50);
    }
}
