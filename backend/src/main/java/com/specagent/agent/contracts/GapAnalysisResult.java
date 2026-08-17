package com.specagent.agent.contracts;

import java.util.List;

/**
 * Result of a gap analysis task: what the current requirement state is missing,
 * which conflicts exist, and whether the state is ready for spec drafting.
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