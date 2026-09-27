package com.specagent.mcp.provider;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityProvider;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.domain.McpToolResult;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件名:McpToolCapabilityProvider.java
 *
 * 用途:动态 {@link CapabilityProvider} 实现——把每个对 Agent 可见的连接上
 * 发现的每一个 MCP 工具,映射成独立的有界 CapabilityDescriptor,id 形如
 * {@code mcp.<connectionId>.<toolName>}。
 *
 * 一个 MCP Server 不等于一个能力——一个带二十个工具的 Server 会产生二十个
 * 动态描述符。启用/状态过滤由 Provider 负责:被禁用或 FAILED 的连接不会返回
 * 任何描述符,因此它们在构造上就会从规划器候选集中消失。注册表永远不知道
 * GitHub/Slack 等具体服务——它只看到这个 Provider。
 *
 * 对外部 MCP 工具的副作用分类采取保守策略:Server 声明的 readOnlyHint 被视为
 * 不可信元数据;任何无法确认其只读行为的工具,默认归入需要策略确认的副作用
 * 分类。未知永远不等于 NONE。
 */
@Component
public class McpToolCapabilityProvider implements CapabilityProvider {

    private final McpConnectionLookupPort connectionLookup;
    private final McpDiscoveryService discoveryService;
    private final McpConnectionRuntime connectionRuntime;

    public McpToolCapabilityProvider(McpConnectionLookupPort connectionLookup,
                                     McpDiscoveryService discoveryService,
                                     McpConnectionRuntime connectionRuntime) {
        this.connectionLookup = connectionLookup;
        this.discoveryService = discoveryService;
        this.connectionRuntime = connectionRuntime;
    }

    @Override
    public String providerName() {
        return "mcp-tools";
    }

    @Override
    public Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
        List<CapabilityDescriptor> descriptors = new ArrayList<>();
        for (McpConnectionTarget connection : connectionLookup.list()) {
            if (!connection.agentVisible()) {
                continue;
            }
            McpDiscovery discovery = safeDiscovery(connection);
            if (discovery == null) {
                continue;
            }
            for (McpTool tool : discovery.tools()) {
                descriptors.add(toDescriptor(connection, tool));
            }
        }
        return descriptors;
    }

    @Override
    public boolean canHandle(String capabilityId) {
        return capabilityId.startsWith("mcp.");
    }

    @Override
    public Optional<CapabilityDescriptor> descriptorFor(String capabilityId) {
        ResolvedTool resolved = resolve(capabilityId);
        if (resolved == null) {
            return Optional.empty();
        }
        if (!resolved.connection().agentVisible()) {
            return Optional.empty();
        }
        McpDiscovery discovery = safeDiscovery(resolved.connection());
        if (discovery == null) {
            return Optional.empty();
        }
        return discovery.tools().stream()
                .filter(tool -> tool.name().equals(resolved.toolName()))
                .findFirst()
                .map(tool -> toDescriptor(resolved.connection(), tool));
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        ResolvedTool resolved = resolve(invocation.capabilityId());
        if (resolved == null || !resolved.connection().agentVisible()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), invocation.capabilityId(),
                    "MCP capability is not available (connection disabled or unknown)");
        }
        McpToolResult result = connectionRuntime.callTool(resolved.connection(),
                resolved.toolName(), invocation.arguments());
        if (!result.success()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), invocation.capabilityId(),
                    result.errorMessage());
        }
        return new CapabilityResult(
                invocation.invocationId(), invocation.invocationKey(),
                invocation.capabilityId(), CapabilityResult.Status.SUCCEEDED,
                result.content(),
                List.of(),
                Map.of("kind", "MCP_TOOL",
                        "connectionId", resolved.connection().connectionId(),
                        "toolName", resolved.toolName()),
                List.of());
    }

    private CapabilityDescriptor toDescriptor(McpConnectionTarget connection, McpTool tool) {
        return new CapabilityDescriptor(
                "mcp." + connection.connectionId() + "." + tool.name(),
                "1",
                tool.description(),
                tool.inputSchema(),
                Map.of(),
                // 保守的信任边界:Server 声明的 readOnlyHint 属于不可信元数据。
                // 缺失/未知的行为绝不能默认为 NONE。
                isConfidentlyReadOnly(tool),
                sideEffectClass(tool),
                List.of(),
                List.of());
    }

    private boolean isConfidentlyReadOnly(McpTool tool) {
        Object hint = tool.annotations() == null ? null
                : tool.annotations().get("readOnlyHint");
        return Boolean.TRUE.equals(hint);
    }

    private SideEffectClass sideEffectClass(McpTool tool) {
        boolean readOnly = isConfidentlyReadOnly(tool);
        boolean destructive = tool.annotations() != null
                && Boolean.TRUE.equals(tool.annotations().get("destructiveHint"));
        if (destructive) {
            return SideEffectClass.EXTERNAL_IRREVERSIBLE;
        }
        if (readOnly) {
            return SideEffectClass.NONE;
        }
        // 行为未知时保持保守:归入需要策略确认的分类。
        return SideEffectClass.EXTERNAL_REVERSIBLE;
    }

    private McpDiscovery safeDiscovery(McpConnectionTarget connection) {
        try {
            return discoveryService.discover(connection);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 把 {@code mcp.<connId>.<tool>} 拆解回连接 + 工具名。 */
    private ResolvedTool resolve(String capabilityId) {
        if (!capabilityId.startsWith("mcp.")) {
            return null;
        }
        String rest = capabilityId.substring("mcp.".length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            return null;
        }
        String connectionId = rest.substring(0, dot);
        String toolName = rest.substring(dot + 1);
        return connectionLookup.findByConnectionId(connectionId)
                .map(connection -> new ResolvedTool(connection, toolName))
                .orElse(null);
    }

    private record ResolvedTool(McpConnectionTarget connection, String toolName) {
    }
}
