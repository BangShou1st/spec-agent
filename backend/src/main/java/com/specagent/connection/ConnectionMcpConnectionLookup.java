package com.specagent.connection;

import com.specagent.connection.Connection;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ConnectionMcpConnectionLookup.java
 *
 * 用途:在 {@link ConnectionRepository} 之上,对 MCP 侧的
 * {@link McpConnectionLookupPort} 做薄实现,把已保存的连接投影给 MCP 运行时。
 *
 * 自身不新增任何持久化逻辑:每次读取就是既有的 repository 查询,再加一次
 * 到 {@link McpConnectionTarget} 的单一投影。它的唯一职责是保持该端口依赖
 * 方向不变——mcp 包永远不 import connection 包,同时连接存储仍是唯一事实源。
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

    /** 把一条已保存的 Connection 投影成 MCP 可见的窄视图。 */
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
