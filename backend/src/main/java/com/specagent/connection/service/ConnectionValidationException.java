package com.specagent.connection.service;

/**
 * Typed validation failure for Connection management input.
 * Maps to 400 VALIDATION_ERROR with a stable safe message.
 */
public class ConnectionValidationException extends RuntimeException {

    public ConnectionValidationException(String message) {
        super(message);
    }
}
