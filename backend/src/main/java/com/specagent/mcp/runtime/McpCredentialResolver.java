package com.specagent.mcp.runtime;

/**
 * Credential resolution seam owned by the MCP runtime: hands the transport
 * the plaintext token for an opaque credential reference, or {@code null}
 * when the reference is absent/blank or the credential row no longer exists
 * (the historical "credential row gone — treated as unauthenticated"
 * semantics). Implementations live on the connection side; plaintext must
 * never reach descriptors, traces, capability results, exception messages,
 * API responses, or logs.
 */
public interface McpCredentialResolver {

    /** Plaintext token for an existing ref, or null (unauthenticated). */
    String resolveOrNull(String credentialRef);
}
