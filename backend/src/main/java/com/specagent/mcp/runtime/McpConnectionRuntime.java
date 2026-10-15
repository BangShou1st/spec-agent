package com.specagent.mcp.runtime;

import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpToolResult;
import com.specagent.mcp.transport.McpClientFactory;
import com.specagent.mcp.transport.McpTransportException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 文件名:McpConnectionRuntime.java
 *
 * 用途:为一个已保存的 Connection 管理 MCP 协议生命周期:打开 → 测试 →
 * 发现 → 关闭。Connection(产品状态)与 MCP(协议)保持分离;本类是桥梁,
 * 针对连接的投影配置和凭据引用调用传输层——它从不直接接触连接领域对象或
 * 密钥存储。
 *
 * {@code testAndDiscover} 绝不调用任意写操作:只建立协议、初始化并发现
 * 原始类型(工具/资源/提示)。
 */
@Service
public class McpConnectionRuntime {

    private final McpClientFactory clientFactory;
    private final McpCredentialResolver credentialResolver;
    private final McpConnectionLookupPort connectionLookup;

    public McpConnectionRuntime(McpClientFactory clientFactory,
                                McpCredentialResolver credentialResolver,
                                McpConnectionLookupPort connectionLookup) {
        this.clientFactory = clientFactory;
        this.credentialResolver = credentialResolver;
        this.connectionLookup = connectionLookup;
    }

    public McpDiscovery testAndDiscover(McpConnectionTarget connection) {
        try (McpClientFactory.Session session = openSession(connection)) {
            return session.discover();
        } catch (McpTransportException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpTransportException("MCP session failed: "
                    + ex.getClass().getSimpleName());
        }
    }

    /** 基于连接的配置 + 凭据,打开一个可用的协议会话。 */
    public McpClientFactory.Session openSession(McpConnectionTarget connection) {
        requireCurrent(connection);
        String authHeader = resolveAuthHeader(connection);
        requireCurrent(connection);
        McpClientFactory.Session session = clientFactory.open(connection.serverUrl(), Map.of(), authHeader);
        try {
            requireCurrent(connection);
            return new GuardedSession(connection, session);
        } catch (RuntimeException ex) {
            try { session.close(); } catch (RuntimeException ignored) { }
            throw ex;
        }
    }

    /** 每次查询重新验证当前主体；旧配置不得在轮换、禁用或撤权后继续使用。 */
    public void requireCurrent(McpConnectionTarget expected) {
        try {
            if (expected == null || !connectionLookup.findByRowId(expected.rowId())
                    .filter(expected::equals).isPresent()) {
                throw new McpTransportException("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
            }
        } catch (McpTransportException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpTransportException("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
        }
    }

    public McpConnectionTarget requireVisibleTarget(java.util.UUID rowId) {
        try {
            return connectionLookup.findByRowId(rowId).orElseThrow(() ->
                    new McpTransportException("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE"));
        } catch (McpTransportException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpTransportException("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
        }
    }

    private final class GuardedSession implements McpClientFactory.Session {
        private final McpConnectionTarget target;
        private final McpClientFactory.Session delegate;

        private GuardedSession(McpConnectionTarget target, McpClientFactory.Session delegate) {
            this.target = target;
            this.delegate = delegate;
        }

        @Override public McpDiscovery discover() {
            requireCurrent(target);
            McpDiscovery result = delegate.discover();
            requireCurrent(target);
            return result;
        }

        @Override public McpToolResult callTool(String name, Map<String, Object> arguments) {
            requireAgentAccess();
            McpToolResult result = delegate.callTool(name, arguments);
            requireAgentAccess();
            return result;
        }

        @Override public com.specagent.mcp.domain.McpResourceContent readResource(String uri) {
            requireAgentAccess();
            var result = delegate.readResource(uri);
            requireAgentAccess();
            return result;
        }

        private void requireAgentAccess() {
            requireCurrent(target);
            if (!target.agentVisible())
                throw new McpTransportException("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
        }

        @Override public void close() { delegate.close(); }
    }

    public McpToolResult callTool(McpConnectionTarget connection, String toolName,
                                  Map<String, Object> arguments) {
        try (McpClientFactory.Session session = openSession(connection)) {
            return session.callTool(toolName, arguments == null ? Map.of() : arguments);
        } catch (McpTransportException ex) {
            return McpToolResult.failure(ex.getMessage());
        } catch (RuntimeException ex) {
            return McpToolResult.failure("MCP tool call failed: "
                    + ex.getClass().getSimpleName());
        }
    }

    /** 通过全新会话读取一个资源(无状态、安全)。 */
    public com.specagent.mcp.domain.McpResourceContent openAndRead(McpConnectionTarget connection,
                                                                   String uri) {
        try (McpClientFactory.Session session = openSession(connection)) {
            return session.readResource(uri);
        } catch (McpTransportException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new McpTransportException("MCP resource read failed: "
                    + ex.getClass().getSimpleName());
        }
    }

    private String resolveAuthHeader(McpConnectionTarget connection) {
        if(!"NONE".equals(connection.authKind()) && !"BEARER".equals(connection.authKind()))
            throw new McpTransportException("MCP_CREDENTIAL_UNAVAILABLE");
        if("NONE".equals(connection.authKind()) && connection.credentialRef()!=null && !connection.credentialRef().isBlank())
            throw new McpTransportException("MCP_CREDENTIAL_UNAVAILABLE");
        if("BEARER".equals(connection.authKind()) && (connection.credentialRef()==null || connection.credentialRef().isBlank()))
            throw new McpTransportException("MCP_CREDENTIAL_UNAVAILABLE");
        String token = credentialResolver.resolveOrNull(connection.credentialRef());
        if(connection.credentialRef()!=null && !connection.credentialRef().isBlank()
                && (token==null || token.isBlank())) throw new McpTransportException("MCP_CREDENTIAL_UNAVAILABLE");
        return token == null ? null : "Bearer " + token;
    }
}
