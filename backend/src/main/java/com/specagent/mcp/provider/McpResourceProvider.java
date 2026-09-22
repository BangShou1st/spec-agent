package com.specagent.mcp.provider;

import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Exposes MCP resources as retrievable external context with provenance.
 * Resources are never flattened into Tools and never become confirmed Graph
 * truth — they are evidence retrievable on demand.
 */
@Component
public class McpResourceProvider {

    private final McpConnectionLookupPort connectionLookup;
    private final McpDiscoveryService discoveryService;
    private final McpConnectionRuntime connectionRuntime;

    public McpResourceProvider(McpConnectionLookupPort connectionLookup,
                               McpDiscoveryService discoveryService,
                               McpConnectionRuntime connectionRuntime) {
        this.connectionLookup = connectionLookup;
        this.discoveryService = discoveryService;
        this.connectionRuntime = connectionRuntime;
    }

    /** Lists the resources of an agent-visible connection. */
    public List<McpResource> discoverResources(UUID connectionRowId) {
        McpConnectionTarget connection = requireVisible(connectionRowId);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.resources();
    }

    /** Reads one resource of an agent-visible connection with provenance. */
    public com.specagent.mcp.domain.McpResourceContent read(UUID connectionRowId, String uri) {
        McpConnectionTarget connection = requireVisible(connectionRowId);
        McpDiscovery discovery = discoveryService.discover(connection);
        boolean known = discovery.resources().stream()
                .anyMatch(resource -> uri.equals(resource.uri()));
        if (!known) {
            throw new McpConnectionCommandException(
                    "Resource is not exposed by the connected server");
        }
        return connectionRuntime.openAndRead(connection, uri);
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
