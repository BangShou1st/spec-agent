package com.specagent.agent.decision;

/**
 * Typed failure when the remote agent brain cannot be reached or fails before
 * returning a parseable response. The runtime maps this onto the durable run
 * failure path; no automatic fallback to another planner/provider happens.
 *
 * <p>{@link BrainFailureCode} says <em>which</em> of those causes it was, so the
 * durable failure record and the user-facing copy can stay honest instead of
 * reporting every cause as "brain unavailable".
 */
public class AgentBrainUnavailableException extends RuntimeException {

    private final BrainFailureCode failureCode;

    public AgentBrainUnavailableException(String message, Throwable cause) {
        this(message, cause, BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    public AgentBrainUnavailableException(String message, Throwable cause,
                                          BrainFailureCode failureCode) {
        super(message, cause);
        this.failureCode = failureCode == null
                ? BrainFailureCode.BRAIN_UNAVAILABLE : failureCode;
    }

    /** Classified cause of this failure; never null. */
    public BrainFailureCode failureCode() {
        return failureCode;
    }
}
