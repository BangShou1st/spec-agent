package com.specagent.retrieval.eval;

import java.util.HashSet;
import java.util.List;

/** Pure evaluation helpers; duplicate scope is one retrieval scenario. */
final class RetrievalEvaluationMetrics {

    private RetrievalEvaluationMetrics() {
    }

    static long duplicateOccurrences(List<? extends List<String>> scenarioSelectedRefs) {
        return scenarioSelectedRefs.stream()
                .mapToLong(refs -> refs.size() - new HashSet<>(refs).size())
                .sum();
    }

    static double duplicateRate(List<? extends List<String>> scenarioSelectedRefs) {
        long selectedItems = scenarioSelectedRefs.stream().mapToLong(List::size).sum();
        return selectedItems == 0
                ? 0d
                : (double) duplicateOccurrences(scenarioSelectedRefs) / selectedItems;
    }
}
