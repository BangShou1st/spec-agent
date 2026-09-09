package com.specagent.mcp.runtime;

import com.specagent.connection.credentials.SecretStore;
import com.specagent.connection.domain.Connection;
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
 * connection's configuration and credential reference.
 *
 * <p>{@code testAndDiscover} never invokes arbitrary write operations: it
 * establishes the protocol, initializes, and discovers primitives only.
 */
@Service
public class McpConnectionRuntime {

    private final McpClientFactory clientFactory;
    private final SecretStore secretStore;

    public McpConnectionRuntime(McpClientFactory clientFactory, SecretStore secretStore) {
        this.clientFactory = clientFactory;
        this.secretStore = secretStore;
    }

    public String serverUrl(Connection connection) {
        Object url = connection.config().get("serverUrl");
        return url instanceof String s ? s : "";
    }

    public McpDiscovery testAndDiscover(Connection connection) {
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
    public McpClientFactory.Session openSession(Connection connection) {
        String authHeader = resolveAuthHeader(connection);
        return clientFactory.open(serverUrl(connection), Map.of(), authHeader);
    }

    public McpToolResult callTool(Connection connection, String toolName,
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
    public com.specagent.mcp.domain.McpResourceContent openAndRead(Connection connection,
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

    private String resolveAuthHeader(Connection connection) {
        if (connection.credentialRef() == null || connection.credentialRef().isBlank()) {
            return null;
        }
        if (secretStore.maskedSuffix(connection.credentialRef()) == null) {
            return null; // credential row gone — treated as unauthenticated
        }
        return "Bearer " + secretStore.resolve(connection.credentialRef());
    }
}