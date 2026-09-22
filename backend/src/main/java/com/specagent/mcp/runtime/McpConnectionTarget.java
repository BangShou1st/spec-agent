package com.specagent.mcp.runtime;

import java.util.UUID;

/**
 * MCP-owned projection of a saved Connection: exactly what the protocol
 * layer may see — internal row id (cache/session keys), public connection id
 * (capability ids, error messages), agent visibility, the safe serverUrl,
 * and the opaque credential reference (never the plaintext secret). The
 * connection domain object itself never crosses into {@code com.specagent.mcp}.
 */
public record McpConnectionTarget(
        UUID rowId,
        String connectionId,
        boolean agentVisible,
        String serverUrl,
        String credentialRef) {

    /** Never allow diagnostics to echo the credential reference. */
    @Override
    public String toString() {
        return "McpConnectionTarget[connectionId=" + connectionId
                + ", agentVisible=" + agentVisible
                + ", serverUrl=" + serverUrl + "]";
    }
}
