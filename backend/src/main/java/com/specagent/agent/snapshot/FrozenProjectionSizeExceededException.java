package com.specagent.agent.snapshot;

/**
 * The canonical frozen input projection exceeds the configured size bound.
 * Fails closed instead of truncating: a silently truncated payload must never
 * keep the snapshot's frozen identity.
 */
public class FrozenProjectionSizeExceededException extends RuntimeException {

    public FrozenProjectionSizeExceededException(String message) {
        super(message);
    }
}
