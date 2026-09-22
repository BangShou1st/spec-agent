package com.specagent.connection.credentials;

import com.specagent.mcp.runtime.McpCredentialResolver;
import org.springframework.stereotype.Component;

/**
 * Thin implementation of the MCP-owned {@link McpCredentialResolver} on top
 * of the connection {@link SecretStore}.
 *
 * <p>It preserves the historical MCP runtime semantics exactly: a blank
 * reference or a vanished credential row resolves to {@code null} (the
 * session proceeds unauthenticated) instead of failing. The plaintext token
 * is handed only to the protocol transport and never logged, traced, or
 * embedded in descriptors, capability results, exceptions, or API responses.
 */
@Component
public class McpCredentialResolverAdapter implements McpCredentialResolver {

    private final SecretStore secretStore;

    public McpCredentialResolverAdapter(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    @Override
    public String resolveOrNull(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        if (secretStore.maskedSuffix(credentialRef) == null) {
            return null; // credential row gone — treated as unauthenticated
        }
        return secretStore.resolve(credentialRef);
    }
}
