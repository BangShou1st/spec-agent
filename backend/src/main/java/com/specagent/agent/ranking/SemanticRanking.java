package com.specagent.agent.ranking;

import com.specagent.agent.eligibility.ActionEligibility;

import java.util.HashSet;
import java.util.List;

/** Versioned, model-produced ranking representation; Runtime selects the winner. */
public record SemanticRanking(String protocolVersion,
                              String eligibilityVersion,
                              String eligibilityBasisHash,
                              String inputFingerprint,
                              List<ActionAssessment> assessments,
                              String rankingWeightsVersion) {

    public static final String VERSION = "agent-ranking.v1";

    public SemanticRanking {
        if (!VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("Unknown semantic ranking version: "
                    + protocolVersion);
        }
        if (!ActionEligibility.VERSION.equals(eligibilityVersion)) {
            throw new IllegalArgumentException("Unknown eligibility version: "
                    + eligibilityVersion);
        }
        validateSha256("eligibilityBasisHash", eligibilityBasisHash);
        validateSha256("inputFingerprint", inputFingerprint);
        assessments = assessments == null ? List.of() : List.copyOf(assessments);
        if (assessments.isEmpty()) {
            throw new IllegalArgumentException("Semantic ranking assessments are required");
        }
        if (assessments.stream().anyMatch(assessment -> assessment == null)) {
            throw new IllegalArgumentException("Semantic ranking assessments cannot be null");
        }
        if (assessments.stream().map(ActionAssessment::family).distinct().count()
                != assessments.size()) {
            throw new IllegalArgumentException("Duplicate semantic ranking family");
        }
        if (rankingWeightsVersion == null || rankingWeightsVersion.isBlank()) {
            throw new IllegalArgumentException("Ranking weights version is required");
        }
    }

    private static void validateSha256(String name, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
    }
}
