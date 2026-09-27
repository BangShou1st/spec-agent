package com.specagent.connection;

import com.specagent.common.Ids;
import com.specagent.connection.credentials.SecretStore;
import com.specagent.connection.Connection;
import com.specagent.connection.ConnectionKind;
import com.specagent.connection.ConnectionStatus;
import com.specagent.connection.ConnectionMcpConnectionLookup;
import com.specagent.connection.ConnectionRepository;
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
 * 文件名:ConnectionLifecycleService.java
 *
 * 用途:已保存 Connection 的生命周期服务,负责连接的创建、测试、连接、
 * 刷新、启停、更新与删除,以及凭据引用的写入与清理。
 *
 * 产品层的 Connection 与 MCP 协议保持分离:本服务只管状态、凭据引用
 * 和启用开关;MCP 执行通过 {@code McpDiscoveryService} 和 transport 完成。
 *
 * 状态流转:CREATED(已落库) -> TESTED(测试通过但尚未连接) ->
 * CONNECTED(发现结果已持久化)。DISABLED/FAILED 状态的连接对 planner
 * 候选不可见;只有测试/发现成功的连接才对 agent 可见。
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

    /** 供 API 响应使用的掩码凭据后缀;未配置凭据时返回 null。 */
    public String maskedSuffix(Connection connection) {
        if (connection.credentialRef() == null || connection.credentialRef().isBlank()) {
            return null;
        }
        return secretStore.maskedSuffix(connection.credentialRef());
    }

    /** 创建一条已保存的 Connection 记录(不产生任何网络活动)。 */
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
     * 测试连接(握手 + 发现缓存)。绝不写远端。整体上不加事务:即使
     * discovery 抛异常,FAILED 状态的更新也必须提交,因此在捕获失败之后
     * 用独立事务单独执行。
     */
    public McpDiscovery test(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        try {
            McpDiscovery discovery = discoveryService.discoverLive(
                    ConnectionMcpConnectionLookup.toTarget(connection));
            markStatus(connection.id(), ConnectionStatus.TESTED, null);
            return discovery;
        } catch (McpTransportException ex) {
            markStatus(connection.id(), ConnectionStatus.FAILED, ex.getMessage());
            throw new ConnectionCommandException(ex.getMessage());
        }
    }

    /** 连接:测试 + 持久化发现结果,状态置为 CONNECTED。 */
    public McpDiscovery connect(UUID connectionId) {
        Connection connection = requireConnection(connectionId);
        try {
            McpDiscovery discovery = discoveryService.discoverLive(
                    ConnectionMcpConnectionLookup.toTarget(connection));
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

    /** 从真实服务器重新拉取发现结果。 */
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

    /** 供公开管理 API 使用的产品层解析:按 connectionId 查找连接。 */
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

    /** config 的统一安全校验:config 只是非敏感元数据,绝不承载密钥。 */
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
