package com.specagent.agent.ranking;

/** Bounded semantic dimensions emitted by the ranking model. */
public record RankingScores(int blockerClosure,
                            int goalProgress,
                            int externalNeed,
                            int completionProximity,
                            int payloadReadiness) {

    public RankingScores {
        validate("blockerClosure", blockerClosure);
        validate("goalProgress", goalProgress);
        validate("externalNeed", externalNeed);
        validate("completionProximity", completionProximity);
        validate("payloadReadiness", payloadReadiness);
    }

    private static void validate(String name, int value) {
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException(name + " must be between 0 and 2");
        }
    }
}
