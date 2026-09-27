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

    public McpConnectionRuntime(McpClientFactory clientFactory,
                                McpCredentialResolver credentialResolver) {
        this.clientFactory = clientFactory;
        this.credentialResolver = credentialResolver;
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
        String authHeader = resolveAuthHeader(connection);
        return clientFactory.open(connection.serverUrl(), Map.of(), authHeader);
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
        String token = credentialResolver.resolveOrNull(connection.credentialRef());
        // 引用缺失或凭据行已消失时解析为 null——会话以未认证方式继续,
        // 与既有行为保持一致。
        return token == null ? null : "Bearer " + token;
    }
}
