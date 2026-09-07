package com.specagent.agent.loop;

import java.util.UUID;

/**
 * The continuation coordinator's answer for one terminal run.
 *
 * <p>{@code eligible} is true for exactly one verdict,
 * {@link ContinuationVerdict#EXECUTED_NEW_OBSERVATION}: a next autonomous
 * cycle may legally be created (Slice 2+). Every other verdict parks or
 * ends the chain. The decision carries no semantic recommendation.
 */
public record ContinuationDecision(UUID runId,
                                    ContinuationVerdict verdict,
                                    String reason) {

    public ContinuationDecision {
        if (runId == null) {
            throw new IllegalArgumentException("Continuation decision requires a run id");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("Continuation decision requires a verdict");
        }
        reason = reason == null ? "" : reason;
    }

    /**
     * True for exactly one verdict,
     * {@link ContinuationVerdict#EXECUTED_NEW_OBSERVATION}: a next autonomous
     * cycle may legally be created (Slice 2+). Every other verdict parks or
     * ends the chain. Eligibility derives from the verdict — it is never
     * stored as independent state.
     */
    public boolean eligible() {
        return verdict == ContinuationVerdict.EXECUTED_NEW_OBSERVATION;
    }

    static ContinuationDecision of(UUID runId, ContinuationVerdict verdict, String reason) {
        return new ContinuationDecision(runId, verdict, reason);
    }
}
