package com.specagent.agent.ranking;

/** Fail-closed ranking contract or winner-selection error. */
public class SemanticRankingException extends IllegalArgumentException {

    public SemanticRankingException(String message) {
        super(message);
    }
}
