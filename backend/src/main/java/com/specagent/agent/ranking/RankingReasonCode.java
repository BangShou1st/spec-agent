package com.specagent.agent.ranking;

/** Closed, bounded reasons that make a semantic ranking auditable. */
public enum RankingReasonCode {
    MISSING_USER_INFORMATION,
    UNRESOLVED_USER_CHOICE,
    EXTERNAL_INFORMATION_REQUIRED,
    GROUNDED_ARGUMENTS_AVAILABLE,
    GOAL_SATISFIED,
    MATERIAL_NOVELTY,
    NOT_NEEDED
}
