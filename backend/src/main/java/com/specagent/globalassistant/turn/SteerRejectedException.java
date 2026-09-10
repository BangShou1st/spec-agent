package com.specagent.globalassistant.turn;

/** Typed steer rejection. No message parsing at API boundary. */
public class SteerRejectedException extends RuntimeException {
    public enum Reason {
        BLANK,
        TOO_LONG,
        RUN_NOT_FOUND,
        THREAD_MISMATCH,
        STALE_TARGET,
        INVALID
    }

    private final Reason reason;

    public SteerRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
