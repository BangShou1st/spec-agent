package com.specagent.mcp.provider;

/**
 * Typed failure for MCP asset access against a connection (unknown connection,
 * not agent-visible, resource/prompt not exposed). MCP-owned so the runtime
 * never imports the connection service package; the API edge maps it to the
 * exact same public contract as the connection-side command rejection
 * (400 CONNECTION_COMMAND_REJECTED). Raw provider/stack details never reach
 * callers.
 */
public class McpConnectionCommandException extends RuntimeException {

    public McpConnectionCommandException(String message) {
        super(message);
    }
}
