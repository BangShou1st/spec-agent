package com.specagent.mcp.domain;

import java.util.Map;

/**
 * Normalized result of a single MCP tool call. Provider outputs are untrusted
 * external data: they are validated/bounded here, carry provenance, and are
 * never presented as confirmed Graph truth.
 */
public record McpToolResult(
        boolean success,
        Map<String, Object> content,
        String errorMessage) {

    public McpToolResult {
        content = content == null ? Map.of() : Map.copyOf(content);
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static McpToolResult ok(Map<String, Object> content) {
        return new McpToolResult(true, content, "");
    }

    public static McpToolResult failure(String message) {
        return new McpToolResult(false, Map.of(), message);
    }
}