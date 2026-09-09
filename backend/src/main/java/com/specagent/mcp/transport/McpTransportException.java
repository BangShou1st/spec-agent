package com.specagent.mcp.transport;

/**
 * Typed failure for MCP transport/handshake/discovery problems. Raw SDK
 * exception class names may be referenced for diagnosis, but provider
 * exception/stack details never reach the model or user.
 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}