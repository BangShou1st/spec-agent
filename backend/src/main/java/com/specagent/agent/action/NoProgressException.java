package com.specagent.agent.action;

/**
 * Thrown when the decision loop detects repeated or no-progress actions.
 * The runtime must stop the loop and report the condition rather than
 * continuing an unproductive cycle.
 */
public class NoProgressException extends RuntimeException {

    public NoProgressException(String message) {
        super(message);
    }
}
