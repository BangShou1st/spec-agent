package com.specagent.agent.runtime;

/**
 * Thrown when a queued run's recorded execution target no longer matches the
 * live graph state at claim time (for example the project switched to another
 * active route while the run was waiting). The cycle must fail closed instead
 * of executing against a target the user was no longer looking at.
 */
public class StaleRunTargetException extends RuntimeException {

    public StaleRunTargetException(String message) {
        super(message);
    }
}
