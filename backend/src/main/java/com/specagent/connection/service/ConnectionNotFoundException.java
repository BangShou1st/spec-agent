package com.specagent.connection.service;

/**
 * Typed not-found for Connection management by product-level connectionId.
 *
 * <p>Maps to 404 CONNECTION_NOT_FOUND with a stable safe message.
 * Raw ids and internals never leak; the message carries only the
 * product-level connectionId supplied by the caller.
 */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(String connectionId) {
        super("Connection not found: " + connectionId);
    }
}
