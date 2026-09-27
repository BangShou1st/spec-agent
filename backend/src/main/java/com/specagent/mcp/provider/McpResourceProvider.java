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
 * 文件名:McpResourceProvider.java
 *
 * 用途:把 MCP 资源暴露为可检索、带溯源的外部上下文。资源绝不会被打平成
 * Tool,也绝不会变成已确认的图谱事实——它们只是可按需检索的证据。
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

    /** 列出某个对 Agent 可见的连接上的资源。 */
    public List<McpResource> discoverResources(UUID connectionRowId) {
        McpConnectionTarget connection = requireVisible(connectionRowId);
        McpDiscovery discovery = discoveryService.discover(connection);
        return discovery.resources();
    }

    /** 读取某个对 Agent 可见的连接上的一个资源,结果附带溯源信息。 */
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
