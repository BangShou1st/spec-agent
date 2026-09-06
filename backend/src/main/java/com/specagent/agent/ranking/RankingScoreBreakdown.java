package com.specagent.agent.ranking;

/** Auditable score vector; the total is derived, never model-authored. */
public record RankingScoreBreakdown(int priorityClass,
                                    int blockerClosure,
                                    int goalProgress,
                                    int externalNeed,
                                    int completionProximity,
                                    int payloadReadiness,
                                    int evidence,
                                    int total) {
}
