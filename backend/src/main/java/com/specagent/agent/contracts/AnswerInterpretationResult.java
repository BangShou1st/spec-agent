package com.specagent.agent.contracts;

import java.util.List;

/**
 * Interpretation of one user answer: which parts are confirmed, assumed,
 * unresolved, or in conflict with existing requirement state.
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