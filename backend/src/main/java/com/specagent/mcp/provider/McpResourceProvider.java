package com.specagent.mcp.provider;

import com.specagent.connection.domain.Connection;
import com.specagent.connection.persistence.ConnectionRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes MCP resources as retrievable external context with provenance.
 * Resources are never flattened into Tools and never become confirmed Graph
 * truth — they are evidence retrievable on demand.
 */
@Component
public class McpResourceProvider {

    private final ConnectionRepository connectionRepository;
    private final McpDiscoveryService discoveryService;
    private final McpConnectionRuntime connectionRuntime;

    public McpResourceProvider(ConnectionRepository connectionRepository,
                               McpDiscoveryService discoveryService,
                               McpConnectionRuntime connectionRuntime) {
        this.connectionRepository = connectionRepository;
        this.discoveryService = discoveryService;
        this.connectionRuntime = connectionRuntime;
    }

    public List<McpResource> discoverResources(Connection connection) {
        listAgentVisible(connection);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.resources();
    }

    /** Reads one resource of an agent-visible connection with provenance. */
    public com.specagent.mcp.domain.McpResourceContent read(UUID connectionRowId, String uri) {
        Connection connection = connectionRepository.findById(connectionRowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connection not found: " + connectionRowId));
        listAgentVisible(connection);
        McpDiscovery discovery = discoveryService.discover(connection);
        boolean known = discovery.resources().stream()
                .anyMatch(resource -> uri.equals(resource.uri()));
        if (!known) {
            throw new IllegalArgumentException(
                    "Resource is not exposed by the connected server: " + uri);
        }
        return connectionRuntime.openAndRead(connection, uri);
    }

    private void listAgentVisible(Connection connection) {
        if (!connection.agentVisible()) {
            throw new IllegalArgumentException(
                    "Connection is not agent-visible: " + connection.connectionId());
        }
    }
}