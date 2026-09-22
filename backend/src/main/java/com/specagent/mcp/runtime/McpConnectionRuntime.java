package com.specagent.mcp.runtime;

import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpToolResult;
import com.specagent.mcp.transport.McpClientFactory;
import com.specagent.mcp.transport.McpTransportException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Owns the MCP protocol lifecycle for one saved Connection: open -> test ->
 * discover -> close. Connection (product state) and MCP (protocol) stay
 * separate; this class is the bridge that invokes the transport for a
 * connection's projected configuration and credential reference — it never
 * sees the connection domain object or the secret store directly.
 *
 * <p>{@code testAndDiscover} never invokes arbitrary write operations: it
 * establishes the protocol, initializes, and discovers primitives only.
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

    /** Opens a live session bound to the connection's config + credential. */
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

    /** Reads one resource through a fresh session (stateless, safe). */
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
        // Missing ref or vanished credential row resolves to null — the
        // session proceeds unauthenticated, exactly as before.
        return token == null ? null : "Bearer " + token;
    }
}
