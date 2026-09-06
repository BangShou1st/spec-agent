package com.specagent.agent.ranking;

/** Versioned generic scorecard weights; no action family receives a fixed priority. */
public record RankingWeights(String version,
                             int priorityClassWeight,
                             int blockerClosureWeight,
                             int goalProgressWeight,
                             int externalNeedWeight,
                             int completionProximityWeight,
                             int payloadReadinessWeight,
                             int evidenceWeight) {

    public static final String VERSION = "semantic-ranking-weights.v1";

    public RankingWeights {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Ranking weights version is required");
        }
        validateWeight("priorityClassWeight", priorityClassWeight);
        validateWeight("blockerClosureWeight", blockerClosureWeight);
        validateWeight("goalProgressWeight", goalProgressWeight);
        validateWeight("externalNeedWeight", externalNeedWeight);
        validateWeight("completionProximityWeight", completionProximityWeight);
        validateWeight("payloadReadinessWeight", payloadReadinessWeight);
        validateWeight("evidenceWeight", evidenceWeight);
    }

    public static RankingWeights defaults() {
        return new RankingWeights(VERSION, 1, 3, 3, 4, 2, 1, 1);
    }

    private static void validateWeight(String name, int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException(name + " must be between 0 and 10");
        }
    }
}
