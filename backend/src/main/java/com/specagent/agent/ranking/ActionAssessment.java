package com.specagent.agent.ranking;

import com.specagent.agent.contract.ActionFamily;

import java.util.HashSet;
import java.util.List;

/** One model-produced semantic assessment for one eligible action family. */
public record ActionAssessment(ActionFamily family,
                               boolean applicable,
                               RankingPriorityClass priorityClass,
                               List<RankingReasonCode> reasonCodes,
                               List<String> evidenceRefs,
                               RankingScores scores) {

    public ActionAssessment {
        if (family == null) {
            throw new IllegalArgumentException("Ranking assessment family is required");
        }
        if (priorityClass == null) {
            throw new IllegalArgumentException("Ranking assessment priority class is required");
        }
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        if (reasonCodes.stream().anyMatch(code -> code == null)
                || reasonCodes.size() != new HashSet<>(reasonCodes).size()) {
            throw new IllegalArgumentException("Ranking reason codes must be non-null and unique");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (evidenceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank())
                || evidenceRefs.size() != new HashSet<>(evidenceRefs).size()) {
            throw new IllegalArgumentException("Ranking evidence refs must be non-blank and unique");
        }
        if (applicable && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Applicable ranking assessment requires evidence refs");
        }
        if (scores == null) {
            throw new IllegalArgumentException("Ranking assessment scores are required");
        }
    }
}
