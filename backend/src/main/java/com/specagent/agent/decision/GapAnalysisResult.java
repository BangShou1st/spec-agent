package com.specagent.agent.decision;

import java.util.List;

/**
 * 文件名:GapAnalysisResult.java
 *
 * 用途:差距分析(gap analysis)任务的结果:当前需求状态缺少什么、
 * 存在哪些冲突,以及该状态是否已具备起草 spec 的条件。
 */
public record GapAnalysisResult(
        List<String> missingAspects,
        List<String> conflicts,
        List<String> assumptions,
        boolean readyForSpec
) {
    public GapAnalysisResult {
        missingAspects = missingAspects == null ? List.of() : List.copyOf(missingAspects);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }
}