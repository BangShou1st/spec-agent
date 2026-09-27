package com.specagent.agent.decision;

import java.util.List;

/**
 * 文件名:AnswerInterpretationResult.java
 *
 * 用途:对单条用户回答的解读结果:哪些部分是已确认的、属于假设的、
 * 尚未消解的,或与既有需求状态相冲突的。
 */
public record AnswerInterpretationResult(
        List<String> confirmedTexts,
        List<String> assumedTexts,
        List<String> unresolvedTexts,
        List<String> conflictTexts
) {
    public AnswerInterpretationResult {
        confirmedTexts = confirmedTexts == null ? List.of() : List.copyOf(confirmedTexts);
        assumedTexts = assumedTexts == null ? List.of() : List.copyOf(assumedTexts);
        unresolvedTexts = unresolvedTexts == null ? List.of() : List.copyOf(unresolvedTexts);
        conflictTexts = conflictTexts == null ? List.of() : List.copyOf(conflictTexts);
    }
}