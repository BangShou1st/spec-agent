package com.specagent.model.provider;

/**
 * Cooperative cancellation signal for a streaming provider call. Thrown when
 * the fragment listener declines further content, which only happens because
 * the owning run was cancelled. Never synthesized for provider errors,
 * timeouts, or malformed output. Must propagate unwrapped so the runtime can
 * terminalize the run as CANCELLED instead of FAILED.
 */
public final class StreamCancelledException extends RuntimeException {

    public StreamCancelledException(String message) {
        super(message);
    }
}