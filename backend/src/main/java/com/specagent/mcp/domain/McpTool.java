package com.specagent.mcp.domain;

import java.util.Map;

/**
 * Normalized, protocol-neutral MCP tool primitive. External server metadata
 * (name/description/schema) is treated as untrusted input: it is bound and
 * normalized here before any capability projection; it can never determine
 * runtime-owned policy facts (permissions, side-effect class, approval).
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations) {

    public McpTool {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        description = description == null ? "" : description;
    }
}