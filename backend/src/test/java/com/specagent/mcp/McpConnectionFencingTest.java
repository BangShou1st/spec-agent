package com.specagent.mcp;

import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpToolResult;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.runtime.McpCredentialResolver;
import com.specagent.mcp.transport.McpClientFactory;
import com.specagent.mcp.transport.McpTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class McpConnectionFencingTest {
    private final McpClientFactory factory = mock(McpClientFactory.class);
    private final McpClientFactory.Session session = mock(McpClientFactory.Session.class);
    private final McpCredentialResolver credentials = mock(McpCredentialResolver.class);
    private final McpConnectionLookupPort lookup = mock(McpConnectionLookupPort.class);
    private final McpConnectionTarget original = new McpConnectionTarget(UUID.randomUUID(),
            "conn-fence", true, "https://mcp.example/tools", null, "NONE", 1);
    private final AtomicReference<McpConnectionTarget> current = new AtomicReference<>(original);
    private final McpConnectionRuntime runtime = new McpConnectionRuntime(factory, credentials, lookup);

    @BeforeEach void savedConnection() {
        when(lookup.findByRowId(original.rowId())).thenAnswer(ignored -> Optional.ofNullable(current.get()));
        when(factory.open(anyString(), anyMap(), any())).thenReturn(session);
    }

    private McpConnectionTarget changed(boolean visible, long version) {
        return new McpConnectionTarget(original.rowId(), original.connectionId(), visible,
                original.serverUrl(), null, "NONE", version);
    }

    @Test void revokedVisibilityRefusesNetworkBeforeResolvingCredentials() {
        current.set(null);
        assertThatThrownBy(() -> runtime.openSession(original))
                .isInstanceOf(McpTransportException.class)
                .hasMessage("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
        verifyNoInteractions(credentials);
        verify(factory, never()).open(anyString(), anyMap(), any());
    }

    @Test void changedCredentialDuringResolutionRefusesOldHeaderBeforeNetwork() {
        when(credentials.resolveOrNull(null)).thenAnswer(ignored -> {
            current.set(changed(true, 2));
            return null;
        });
        assertThatThrownBy(() -> runtime.openSession(original)).isInstanceOf(McpTransportException.class);
        verify(factory, never()).open(anyString(), anyMap(), any());
    }

    @Test void revokeDuringHandshakeClosesTheNewSession() {
        when(factory.open(anyString(), anyMap(), any())).thenAnswer(ignored -> {
            current.set(null);
            return session;
        });
        assertThatThrownBy(() -> runtime.openSession(original)).isInstanceOf(McpTransportException.class);
        verify(session).close();
        verify(session, never()).discover();
    }

    @Test void disablingDuringToolCallDiscardsLatePayloadAndClosesSession() {
        when(session.callTool("inspect", Map.of())).thenAnswer(ignored -> {
            current.set(changed(false, 2));
            return McpToolResult.ok(Map.of("private", "late-data"));
        });
        McpToolResult result = runtime.callTool(original, "inspect", Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("MCP_CONNECTION_CHANGED_OR_INACCESSIBLE");
        verify(session).close();
    }

    @Test void rotateDuringDiscoveryDiscardsOldCatalog() {
        when(session.discover()).thenAnswer(ignored -> {
            current.set(changed(true, 2));
            return new McpDiscovery("server", "v1", List.of(), List.of(), List.of());
        });
        assertThatThrownBy(() -> runtime.testAndDiscover(original)).isInstanceOf(McpTransportException.class);
        verify(session).close();
    }

    @Test void disabledConnectionAllowsManagementDiscoveryButRefusesTools() {
        McpConnectionTarget disabled = changed(false, 2);
        current.set(disabled);
        when(session.discover()).thenReturn(new McpDiscovery("server", "v1", List.of(), List.of(), List.of()));
        assertThat(runtime.testAndDiscover(disabled)).isNotNull();
        assertThat(runtime.callTool(disabled, "inspect", Map.of()).success()).isFalse();
        verify(session, never()).callTool(anyString(), anyMap());
    }

    @Test void revokeDuringCacheReadDiscardsCachedCatalog() {
        var cache = mock(McpDiscoveryCacheRepository.class);
        var discovery = new com.specagent.mcp.runtime.McpDiscoveryService(runtime, cache,
                new com.specagent.common.Json(new com.fasterxml.jackson.databind.ObjectMapper()));
        when(cache.findByConnection(original.rowId(), 1)).thenAnswer(ignored -> {
            current.set(null);
            return Optional.of(new McpDiscoveryCacheRepository.CacheRow(original.rowId(),
                    "[]", "[]", "[]", "fingerprint", java.time.Instant.now()));
        });
        assertThatThrownBy(() -> discovery.discover(original)).isInstanceOf(McpTransportException.class);
        verify(session, never()).discover();
    }

    @Test void hiddenPersonalConnectionCannotReadCacheEvenByIdentifier() {
        current.set(null);
        var cache = mock(McpDiscoveryCacheRepository.class);
        var discovery = new com.specagent.mcp.runtime.McpDiscoveryService(runtime, cache,
                new com.specagent.common.Json(new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThatThrownBy(() -> discovery.findCached(original.rowId())).isInstanceOf(McpTransportException.class);
        verifyNoInteractions(cache);
    }

    @Test void rotatedDiscoveryCannotOverwriteCache() {
        var cache = mock(McpDiscoveryCacheRepository.class);
        var discovery = new com.specagent.mcp.runtime.McpDiscoveryService(runtime, cache,
                new com.specagent.common.Json(new com.fasterxml.jackson.databind.ObjectMapper()));
        when(session.discover()).thenAnswer(ignored -> {
            current.set(changed(true, 2));
            return new McpDiscovery("server", "v1", List.of(), List.of(), List.of());
        });
        assertThatThrownBy(() -> discovery.discoverLive(original)).isInstanceOf(McpTransportException.class);
        verifyNoInteractions(cache);
    }
}
