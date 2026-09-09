package com.specagent.globalassistant.turn;

/** Repository-level duplicate-steer signal. Mapped to 409 at API boundary. */
public class SteerPendingException extends RuntimeException {
    public SteerPendingException(String message, Throwable cause) {
        super(message, cause);
    }
}
