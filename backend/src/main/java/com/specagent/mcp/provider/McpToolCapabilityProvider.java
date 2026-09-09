package com.specagent.mcp.provider;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityProvider;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.persistence.ConnectionRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.domain.McpToolResult;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic {@link CapabilityProvider}: maps every discovered MCP tool of every
 * agent-visible connection into its own bounded CapabilityDescriptor id
 * {@code mcp.<connectionId>.<toolName>}.
 *
 * <p>One MCP server is NOT one capability — one server with twenty tools
 * yields twenty dynamic descriptors. Enabled/status filtering is the
 * provider's responsibility: disabled or FAILED connections return no
 * descriptors, so they disappear from planner candidates by construction.
 * The registry never knows GitHub/Slack/etc. — it only sees this provider.
 *
 * <p>Side-effect classification of foreign MCP tools is conservative: the
 * server's readOnlyHint is treated as untrusted metadata, and any tool whose
 * behavior cannot be confirmed read-only defaults to a side-effect class that
 * requires policy confirmation. Unknown never equals NONE.
 */
@Component
public class McpToolCapabilityProvider implements CapabilityProvider {

    private final ConnectionRepository connectionRepository;
    private final McpDiscoveryService discoveryService;
    private final McpConnectionRuntime connectionRuntime;

    public McpToolCapabilityProvider(ConnectionRepository connectionRepository,
                                     McpDiscoveryService discoveryService,
                                     McpConnectionRuntime connectionRuntime) {
        this.connectionRepository = connectionRepository;
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
        for (Connection connection : connectionRepository.list()) {
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

    private CapabilityDescriptor toDescriptor(Connection connection, McpTool tool) {
        return new CapabilityDescriptor(
                "mcp." + connection.connectionId() + "." + tool.name(),
                "1",
                tool.description(),
                tool.inputSchema(),
                Map.of(),
                // Conservative trust boundary: a server-declared readOnlyHint
                // is untrusted metadata. Absent/unknown behavior must never
                // default to NONE.
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
        // Unknown behavior: conservative. Requires policy confirmation.
        return SideEffectClass.EXTERNAL_REVERSIBLE;
    }

    private McpDiscovery safeDiscovery(Connection connection) {
        try {
            return discoveryService.discover(connection);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Splits {@code mcp.<connId>.<tool>} back into connection + tool. */
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
        Connection connection = connectionRepository.findById(connectionId).orElse(null);
        return connection == null ? null
                : new ResolvedTool(connection, toolName);
    }

    private record ResolvedTool(Connection connection, String toolName) {
    }
}