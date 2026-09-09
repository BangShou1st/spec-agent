package com.specagent.mcp.domain;

import java.util.List;
import java.util.Map;

/**
 * Complete normalized discovery result for one MCP connection. This is the
 * only shape that leaves the {@code mcp} module — SDK classes never cross the
 * boundary.
 */
public record McpDiscovery(
        String serverInfo,
        String protocolVersion,
        List<McpTool> tools,
        List<McpResource> resources,
        List<McpPrompt> prompts) {

    public McpDiscovery {
        tools = tools == null ? List.of() : List.copyOf(tools);
        resources = resources == null ? List.of() : List.copyOf(resources);
        prompts = prompts == null ? List.of() : List.copyOf(prompts);
        serverInfo = serverInfo == null ? "" : serverInfo;
        protocolVersion = protocolVersion == null ? "" : protocolVersion;
    }
}