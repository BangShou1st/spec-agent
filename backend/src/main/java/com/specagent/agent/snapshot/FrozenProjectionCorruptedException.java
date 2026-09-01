package com.specagent.agent.snapshot;

/**
 * A durable frozen input projection failed verification: hash mismatch,
 * unsupported projection version, malformed payload, or a payload bound to a
 * different snapshot identity. Always fails closed — the frozen projection is
 * audit/reproducibility evidence, so it is never silently rebuilt from live
 * records and never overwritten.
 */
public class FrozenProjectionCorruptedException extends RuntimeException {

    public FrozenProjectionCorruptedException(String message) {
        super(message);
    }
}
