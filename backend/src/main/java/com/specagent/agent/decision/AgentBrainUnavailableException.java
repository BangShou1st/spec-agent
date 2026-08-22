package com.specagent.agent.decision;

/**
 * Typed failure when the remote agent brain cannot be reached or fails before
 * returning a parseable response. The runtime maps this onto the durable run
 * failure path; no automatic fallback to another planner/provider happens.
 */
public class AgentBrainUnavailableException extends RuntimeException {

    public AgentBrainUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
