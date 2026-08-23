package com.specagent.agent.action;

/**
 * Thrown when an action proposal's base context no longer matches the current
 * authoritative graph state. The executor must reject stale proposals and the
 * caller should rerun from a fresh snapshot rather than silently rebasing.
 */
public class StaleProposalException extends RuntimeException {

    public StaleProposalException(String message) {
        super(message);
    }
}
