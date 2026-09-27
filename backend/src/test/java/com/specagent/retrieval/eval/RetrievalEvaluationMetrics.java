package com.specagent.retrieval.eval;

import java.util.HashSet;
import java.util.List;

/**
 * 文件名:RetrievalEvaluationMetrics.java
 *
 * 测试目标:检索评估的纯计算辅助工具,统计检索结果的重复引用次数与重复率;
 * 重复度以单个检索场景为统计范围,不做跨场景汇总。
 */
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
