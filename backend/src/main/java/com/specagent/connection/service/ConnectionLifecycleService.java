package com.specagent.connection.service;

import com.specagent.common.Ids;
import com.specagent.connection.credentials.SecretStore;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.domain.ConnectionStatus;
import com.specagent.connection.persistence.ConnectionRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.runtime.McpDiscoveryService;
import com.specagent.mcp.transport.McpTransportException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved-Connection lifecycle. The product Connection and the MCP protocol stay
 * separate: this service owns status/credential-ref/enablement, while MCP
 * execution happens through {@code McpDiscoveryService} and the transport.
 *
 * <p>Status model: CREATED (row saved) -> TESTED (test succeeded but not yet
 * connected) -> CONNECTED (discovery persisted). DISABLED/FAILED connections
 * are invisible to planner candidates. Only test/discovery-successful
 * connections become agent-visible.
 */
@Service
public class ConnectionLifecycleService {

    private final ConnectionRepository repository;
    private final SecretStore secretStore;
    private final McpDiscoveryService discoveryService;

    public ConnectionLifecycleService(ConnectionRepository repository,
                                      SecretStore secretStore,
                                      McpDiscoveryService discoveryService) {
        this.repository = repository;
        this.secretStore = secretStore;
        this.discoveryService = discoveryService;
    }

    /** Creates a saved Connection row (no network activity). */
    @Transactional
    public Connection create(ConnectionKind kind, String name, Map<String, Object> config,
                             String secret) {
        if (name == null || name.isBlank()) {
            throw new ConnectionCommandException("Connection name is required");
        }
        Connection connection = new Connection(
                UUID.randomUUID(), "conn_" + Ids.random().toString().substring(0, 12),
                name.strip(), kind, ConnectionStatus.CREATED, false,
                config == null ? Map.of() : config, null, null,
                Instant.now(), Instant.now());
        repository.insert(connection);
        String credentialRef = null;
        if (secret != null && !secret.isBlank()) {
            credentialRef = secretStore.store(connection.id(), secret);
            repository.updateCredentialRef(connection.id(), credentialRef);
        }
        return new Connection(
                connection.id(), connection.connectionId(), connection.name(),
                connection.kind(), connection.status(), connection.enabled(),
                connection.config(), credentialRef, connection.lastError(),
                connection.createdAt(), connection.updatedAt());
    }

    /**
     * Tests the connection (handshake + discovery cache). Never writes
     * remotely. Not transactional as a whole: the FAILED status update must
     * commit even when discovery throws, so it runs in its own transaction
     * after the failure is caught.
     */
    public McpDiscovery test(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        try {
            McpDiscovery discovery = discoveryService.discoverLive(connection);
            markStatus(connection.id(), ConnectionStatus.TESTED, null);
            return discovery;
        } catch (McpTransportException ex) {
            markStatus(connection.id(), ConnectionStatus.FAILED, ex.getMessage());
            throw new ConnectionCommandException(ex.getMessage());
        }
    }

    /** Connects: test + persist discovery, mark CONNECTED. */
    public McpDiscovery connect(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        try {
            McpDiscovery discovery = discoveryService.discoverLive(connection);
            markStatus(connection.id(), ConnectionStatus.CONNECTED, null);
            return discovery;
        } catch (McpTransportException ex) {
            markStatus(connection.id(), ConnectionStatus.FAILED, ex.getMessage());
            throw new ConnectionCommandException(ex.getMessage());
        }
    }

    @Transactional
    void markStatus(UUID connectionId, ConnectionStatus status, String lastError) {
        repository.updateStatus(connectionId, status, lastError);
    }

    /** Refreshes discovery from the live server. */
    @Transactional
    public McpDiscovery refresh(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        discoveryService.invalidate(connection.id());
        return connect(connectionId);
    }

    @Transactional
    public void enable(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        if (!connection.status().agentVisible()) {
            throw new ConnectionCommandException(
                    "Connection must be tested/connected before enabling: "
                            + connection.connectionId());
        }
        repository.findEnabledByName(connection.name()).ifPresent(existing -> {
            if (!existing.id().equals(connection.id())) {
                throw new ConnectionCommandException(
                        "Another enabled Connection already uses the name '"
                                + connection.name() + "' (disable it first)");
            }
        });
        repository.updateEnabled(connection.id(), true);
    }

    @Transactional
    public void disable(UUID connectionId) {
        repository.updateEnabled(connectionId, false);
    }

    @Transactional
    public void delete(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        if (connection.credentialRef() != null) {
            secretStore.delete(connection.credentialRef());
        }
        discoveryService.invalidate(connection.id());
        repository.delete(connection.id());
    }

    public List<Connection> list() {
        return repository.list();
    }

    public Optional<Connection> find(String connectionId) {
        return repository.findById(connectionId);
    }

    public Optional<Connection> findByRowId(UUID id) {
        return repository.findById(id);
    }

    private Connection requireConnection(UUID connectionId) {
        return repository.findById(connectionId)
                .orElseThrow(() -> new ConnectionCommandException(
                        "Connection not found: " + connectionId));
    }
}