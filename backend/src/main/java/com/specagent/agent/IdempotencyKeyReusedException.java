package com.specagent.agent;

/**
 * Signals that a project-scoped idempotency key was reused for another
 * logical request.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("The idempotency key was already used for a different request.");
    }
}
