package com.specagent.mcp.domain;

/**
 * Normalized MCP prompt primitive. Prompts are discovered/stored assets for
 * inspection — they are never automatically injected as system policy, and
 * server-authored instructions carry no runtime authority.
 */
public record McpPrompt(
        String name,
        String description,
        int argumentCount) {

    public McpPrompt {
        description = description == null ? "" : description;
    }
}