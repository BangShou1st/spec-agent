package com.specagent.mcp.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow connection lookup seam owned by the MCP runtime: resolve the
 * {@link McpConnectionTarget} projections the protocol layer needs. Implemented
 * by the connection side, so {@code com.specagent.mcp} never imports
 * {@code com.specagent.connection} and the connection store stays the single
 * source of truth.
 */
public interface McpConnectionLookupPort {

    /** All saved connections, projected (visibility filtering happens at the consumer). */
    List<McpConnectionTarget> list();

    /** Lookup by internal row id. */
    Optional<McpConnectionTarget> findByRowId(UUID rowId);

    /** Lookup by public product connection id ({@code conn_...}). */
    Optional<McpConnectionTarget> findByConnectionId(String connectionId);
}
