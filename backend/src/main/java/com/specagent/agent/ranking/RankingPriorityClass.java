package com.specagent.agent.ranking;

/** Bounded semantic urgency labels; these are not action-family precedence. */
public enum RankingPriorityClass {
    BLOCKING(2),
    REQUIRED_EXTERNAL_STEP(2),
    DIRECT_COMPLETION(1),
    OPTIONAL_PROGRESS(0),
    DURABLE_MATERIALIZATION(0);

    private final int semanticLevel;

    RankingPriorityClass(int semanticLevel) {
        this.semanticLevel = semanticLevel;
    }

    int semanticLevel() {
        return semanticLevel;
    }
}
