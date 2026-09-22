package com.specagent.retrieval.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
