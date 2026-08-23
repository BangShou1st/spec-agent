package com.specagent.capability;

import java.util.List;

/**
 * Boundary for MCP-server adapters. An MCP server may expose tools,
 * resources, and prompts; an adapter maps them intentionally:
 *
 * <ul>
 *   <li>MCP tools → invokable capabilities with side-effect metadata;</li>
 *   <li>MCP resources → retrievable resource context with provenance;</li>
 *   <li>MCP prompts → reusable prompt assets, never automatic system-policy
 *       override.</li>
 * </ul>
 *
 * The application host owns connections, credentials, permissions, context
 * exposure, and user approvals. No MCP adapter is wired in this stage; when
 * one lands, it must expose its server's primitive kinds through
 * {@link #exposedPrimitiveKinds()} so the registry can classify them.
 */
public interface McpAdapter extends CapabilityAdapter {

    enum PrimitiveKind { TOOL, RESOURCE, PROMPT }

    /** Which MCP primitive kinds this adapter maps. */
    List<PrimitiveKind> exposedPrimitiveKinds();
}
