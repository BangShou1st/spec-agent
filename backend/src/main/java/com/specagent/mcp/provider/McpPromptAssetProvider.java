package com.specagent.mcp.provider;

import com.specagent.connection.domain.Connection;
import com.specagent.connection.service.ConnectionCommandException;
import com.specagent.connection.persistence.ConnectionRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
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

    private final ConnectionRepository connectionRepository;
    private final McpDiscoveryService discoveryService;

    public McpPromptAssetProvider(ConnectionRepository connectionRepository,
                                  McpDiscoveryService discoveryService) {
        this.connectionRepository = connectionRepository;
        this.discoveryService = discoveryService;
    }

    public List<McpPrompt> discoverPrompts(UUID connectionRowId) {
        Connection connection = requireVisible(connectionRowId);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.prompts();
    }

    /** Connection-resolved prompts read for the product-level management API. */
    public List<McpPrompt> discoverPromptsResolved(Connection connection) {
        requireVisibleResolved(connection);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.prompts();
    }

    private Connection requireVisible(UUID connectionRowId) {
        Connection connection = connectionRepository.findById(connectionRowId)
                .orElseThrow(() -> new ConnectionCommandException(
                        "Connection not found: " + connectionRowId));
        if (!connection.agentVisible()) {
            throw new ConnectionCommandException(
                    "Connection is not agent-visible: " + connection.connectionId());
        }
        return connection;
    }

    private void requireVisibleResolved(Connection connection) {
        if (!connection.agentVisible()) {
            throw new ConnectionCommandException(
                    "Connection is not agent-visible: " + connection.connectionId());
        }
    }
}
