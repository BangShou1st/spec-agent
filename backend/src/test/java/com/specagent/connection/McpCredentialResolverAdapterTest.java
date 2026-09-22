package com.specagent.connection;

import com.specagent.connection.credentials.McpCredentialResolverAdapter;
import com.specagent.connection.credentials.SecretStore;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.transport.McpClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Issue #14 credential-seam regression: the MCP runtime resolves connection
 * credentials through the MCP-owned {@link com.specagent.mcp.runtime.McpCredentialResolver}
 * port, never the SecretStore directly. Blank refs and vanished credential
 * rows stay unauthenticated (null auth header), an existing ref produces the
 * historical "Bearer &lt;token&gt;" header, and plaintext never appears in
 * any message or projection.
 */
@ExtendWith(MockitoExtension.class)
class McpCredentialResolverAdapterTest {

    @Mock
    private SecretStore secretStore;

    @Mock
    private McpClientFactory clientFactory;

    private McpConnectionTarget target(String credentialRef) {
        return new McpConnectionTarget(UUID.randomUUID(), "conn-seam", true,
                "https://mcp.example/seam", credentialRef);
    }

    @Test
    void blankOrNullReferenceResolvesToUnauthenticatedWithoutTouchingTheStore() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull(null)).isNull();
        assertThat(resolver.resolveOrNull("  ")).isNull();

        verifyNoInteractions(secretStore);
    }

    @Test
    void vanishedCredentialRowIsTreatedAsUnauthenticated() {
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull("cred:gone")).isNull();

        verify(secretStore, never()).resolve("cred:gone");
    }

    @Test
    void existingCredentialRowResolvesToThePlaintextToken() {
        when(secretStore.maskedSuffix("cred:live")).thenReturn("****-1234");
        when(secretStore.resolve("cred:live")).thenReturn("plain-token");
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);

        assertThat(resolver.resolveOrNull("cred:live")).isEqualTo("plain-token");
    }

    @Test
    void runtimeBuildsTheBearerHeaderOnlyAtTheTransportSeam() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);
        when(secretStore.maskedSuffix("cred:live")).thenReturn("****-1234");
        when(secretStore.resolve("cred:live")).thenReturn("plain-token");
        McpConnectionRuntime runtime = new McpConnectionRuntime(clientFactory, resolver);
        when(clientFactory.open(eq("https://mcp.example/seam"), eq(Map.of()),
                eq("Bearer plain-token"))).thenReturn(mock(McpClientFactory.Session.class));

        runtime.openSession(target("cred:live"));

        verify(clientFactory).open("https://mcp.example/seam", Map.of(), "Bearer plain-token");
    }

    @Test
    void runtimeOpensUnauthenticatedSessionWhenCredentialRowIsGone() {
        McpCredentialResolverAdapter resolver = new McpCredentialResolverAdapter(secretStore);
        when(secretStore.maskedSuffix("cred:gone")).thenReturn(null);
        McpConnectionRuntime runtime = new McpConnectionRuntime(clientFactory, resolver);
        when(clientFactory.open(eq("https://mcp.example/seam"), eq(Map.of()),
                eq(null))).thenReturn(mock(McpClientFactory.Session.class));

        runtime.openSession(target("cred:gone"));

        verify(clientFactory).open("https://mcp.example/seam", Map.of(), null);
    }
}
