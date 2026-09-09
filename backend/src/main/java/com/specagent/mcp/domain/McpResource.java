package com.specagent.mcp.domain;

/**
 * Normalized MCP resource primitive. A resource is retrievable external
 * context with provenance — never confirmed Graph truth and never flattened
 * into a Tool.
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType) {

    public McpResource {
        description = description == null ? "" : description;
        mimeType = mimeType == null ? "text/plain" : mimeType;
    }
}