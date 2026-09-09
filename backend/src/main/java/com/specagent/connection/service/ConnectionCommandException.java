package com.specagent.connection.service;

/**
 * Typed failure for Connection lifecycle operations (create/test/connect/
 * enable/delete/refresh). Raw provider/stack details never reach callers.
 */
public class ConnectionCommandException extends RuntimeException {

    public ConnectionCommandException(String message) {
        super(message);
    }
}