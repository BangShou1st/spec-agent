package com.specagent.retrieval.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:RetrievalMetricsCalculationTest.java
 *
 * 测试目标:验证检索评估指标的计算规则——同一来源跨场景出现不算重复,
 * 单个场景内部重复才计入重复次数与重复率。
 */
class RetrievalMetricsCalculationTest {

    @Test
    void sameSourceAcrossScenariosIsNotDuplicate() {
        assertThat(RetrievalEvaluationMetrics.duplicateOccurrences(List.of(
                List.of("source:X", "source:Y"),
                List.of("source:X", "source:Z"))))
                .isZero();
        assertThat(RetrievalEvaluationMetrics.duplicateRate(List.of(
                List.of("source:X", "source:Y"),
                List.of("source:X", "source:Z"))))
                .isZero();
    }

    @Test
    void duplicateWithinOneScenarioCountsAsOccurrence() {
        assertThat(RetrievalEvaluationMetrics.duplicateOccurrences(List.of(
                List.of("source:X", "source:X", "source:Y"))))
                .isEqualTo(1);
        assertThat(RetrievalEvaluationMetrics.duplicateRate(List.of(
                List.of("source:X", "source:X", "source:Y"))))
                .isEqualTo(1d / 3d);
    }
}
