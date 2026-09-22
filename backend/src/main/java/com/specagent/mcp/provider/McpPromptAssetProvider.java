package com.specagent.mcp.provider;

import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Discovers and stores MCP prompt assets. Prompts are assets for inspection —
 * they never become automatic system policy, and server-authored prompt text
 * carries no runtime authority. Phase one: discover + inspect only.
 */
@Component
public class McpPromptAssetProvider {

    private final McpConnectionLookupPort connectionLookup;
    private final McpDiscoveryService discoveryService;

    public McpPromptAssetProvider(McpConnectionLookupPort connectionLookup,
                                  McpDiscoveryService discoveryService) {
        this.connectionLookup = connectionLookup;
        this.discoveryService = discoveryService;
    }

    public List<McpPrompt> discoverPrompts(UUID connectionRowId) {
        McpConnectionTarget connection = requireVisible(connectionRowId);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.prompts();
    }

    private McpConnectionTarget requireVisible(UUID connectionRowId) {
        McpConnectionTarget connection = connectionLookup.findByRowId(connectionRowId)
                .orElseThrow(() -> new McpConnectionCommandException(
                        "Connection not found: " + connectionRowId));
        if (!connection.agentVisible()) {
            throw new McpConnectionCommandException(
                    "Connection is not agent-visible: " + connection.connectionId());
        }
        return connection;
    }
}
