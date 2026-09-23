package com.specagent.connection;

import com.specagent.connection.Connection;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin implementation of the MCP-owned {@link McpConnectionLookupPort} on top
 * of {@link ConnectionRepository}.
 *
 * <p>It adds no persistence logic of its own: every read is the existing
 * repository statement plus the single projection to
 * {@link McpConnectionTarget}. Its only job is to keep the port's dependency
 * direction intact, so the mcp package never imports the connection package
 * and the connection store stays the single source of truth.
 */
@Component
public class ConnectionMcpConnectionLookup implements McpConnectionLookupPort {

    private final ConnectionRepository repository;

    public ConnectionMcpConnectionLookup(ConnectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<McpConnectionTarget> list() {
        return repository.list().stream().map(ConnectionMcpConnectionLookup::toTarget).toList();
    }

    @Override
    public Optional<McpConnectionTarget> findByRowId(UUID rowId) {
        return repository.findById(rowId).map(ConnectionMcpConnectionLookup::toTarget);
    }

    @Override
    public Optional<McpConnectionTarget> findByConnectionId(String connectionId) {
        return repository.findById(connectionId).map(ConnectionMcpConnectionLookup::toTarget);
    }

    /** Projects a saved Connection into the narrow MCP-visible shape. */
    public static McpConnectionTarget toTarget(Connection connection) {
        Object url = connection.config().get("serverUrl");
        return new McpConnectionTarget(
                connection.id(),
                connection.connectionId(),
                connection.agentVisible(),
                url instanceof String s ? s : "",
                connection.credentialRef());
    }
}
