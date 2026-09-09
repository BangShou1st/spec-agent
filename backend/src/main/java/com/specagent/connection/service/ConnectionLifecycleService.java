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
        String validatedName = validatedName(name);
        Map<String, Object> validatedConfig = validatedConfig(kind, config);
        Connection connection = new Connection(
                UUID.randomUUID(), "conn_" + Ids.random().toString().substring(0, 12),
                validatedName, kind, ConnectionStatus.CREATED, false,
                validatedConfig, null, null,
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

    /** Product-level resolve for the public management API. */
    public Connection requireByConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new ConnectionNotFoundException(String.valueOf(connectionId));
        }
        return repository.findById(connectionId.strip())
                .orElseThrow(() -> new ConnectionNotFoundException(connectionId.strip()));
    }

    public McpDiscovery testByConnectionId(String connectionId) {
        return test(requireByConnectionId(connectionId).id());
    }

    public McpDiscovery connectByConnectionId(String connectionId) {
        return connect(requireByConnectionId(connectionId).id());
    }

    public McpDiscovery refreshByConnectionId(String connectionId) {
        return refresh(requireByConnectionId(connectionId).id());
    }

    @Transactional
    public void enableByConnectionId(String connectionId) {
        enable(requireByConnectionId(connectionId).id());
    }

    @Transactional
    public void disableByConnectionId(String connectionId) {
        disable(requireByConnectionId(connectionId).id());
    }

    @Transactional
    public void deleteByConnectionId(String connectionId) {
        delete(requireByConnectionId(connectionId).id());
    }

    @Transactional
    public Connection updateByConnectionId(String connectionId, String name, Map<String, Object> config, boolean hasName, boolean hasConfig, boolean hasSecret, String secret) {
        Connection current = requireByConnectionId(connectionId);
        String nextName = current.name();
        boolean nameChanged = false;
        if (hasName) {
            String validated = validatedName(name);
            if (!validated.equals(current.name())) {
                nextName = validated;
                nameChanged = true;
            }
        }
        Map<String, Object> nextConfig = current.config();
        boolean configChanged = false;
        if (hasConfig) {
            Map<String, Object> validated = validatedConfig(current.kind(), config);
            if (!validated.equals(current.config())) {
                nextConfig = validated;
                configChanged = true;
            }
        }
        boolean secretChanged = hasSecret;
        if (hasSecret && (secret == null || secret.isBlank())) {
            throw new ConnectionValidationException("Replacement secret must be non-blank");
        }
        if (!nameChanged && !configChanged && !secretChanged) {
            return current;
        }
        if (nameChanged && current.enabled()) {
            String candidate = nextName;
            UUID currentId = current.id();
            repository.findEnabledByName(candidate).ifPresent(existing -> {
                if (!existing.id().equals(currentId)) {
                    throw new ConnectionCommandException("Another enabled Connection already uses the name \u0027" + candidate + "\u0027 (disable it first)");
                }
            });
        }
        String nextCredentialRef = current.credentialRef();
        if (secretChanged) {
            String newRef = secretStore.store(current.id(), secret);
            if (nextCredentialRef != null && !nextCredentialRef.isBlank()) {
                try { secretStore.delete(nextCredentialRef); } catch (RuntimeException ignored) { }
            }
            nextCredentialRef = newRef;
        }
        if (configChanged || secretChanged) {
            discoveryService.invalidate(current.id());
            repository.updateManagement(current.id(), nextName, nextConfig, nextCredentialRef, ConnectionStatus.CREATED, false, null);
        } else {
            repository.updateManagement(current.id(), nextName, nextConfig, nextCredentialRef, current.status(), current.enabled(), current.lastError());
        }
        return repository.findById(current.id()).orElseThrow();
    }

    /** Shared safe-config validation: config is non-secret metadata, never a secret carrier. */
    static Map<String, Object> validatedConfig(ConnectionKind kind, Map<String, Object> config) {
        Map<String, Object> safe = config == null ? Map.of() : Map.copyOf(config);
        for (String key : safe.keySet()) {
            if (key == null || key.isBlank()) {
                throw new ConnectionValidationException("Connection config keys must be non-blank");
            }
            String lower = key.toLowerCase();
            if (lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("passwd") || lower.contains("authorization") || lower.contains("apikey") || lower.contains("api_key") || lower.contains("accesskey") || lower.contains("access_key")) {
                throw new ConnectionValidationException("Connection config must not carry secrets; use the secret field");
            }
        }
        if (kind == ConnectionKind.CUSTOM_MCP) {
            Object url = safe.get("serverUrl");
            if (!(url instanceof String s) || s.isBlank()) {
                throw new ConnectionValidationException("Custom MCP connection requires a non-empty serverUrl");
            }
            if (safe.size() != 1 || !safe.containsKey("serverUrl")) {
                throw new ConnectionValidationException("Custom MCP config supports only serverUrl in this version");
            }
        } else {
            if (!safe.isEmpty()) {
                throw new ConnectionValidationException("This connection kind accepts no config in this version");
            }
        }
        return safe;
    }

    static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new ConnectionValidationException("Connection name is required");
        }
        String trimmed = name.strip();
        if (trimmed.length() > 128) {
            throw new ConnectionValidationException("Connection name must be at most 128 characters");
        }
        return trimmed;
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
