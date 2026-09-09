package com.specagent.mcp;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.SideEffectClass;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.domain.ConnectionStatus;
import com.specagent.connection.persistence.ConnectionRepository;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.provider.McpToolCapabilityProvider;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Dynamic MCP tool visibility without network: connected+enabled connections
 * expose one descriptor per tool; disabled/disconnected connections expose
 * none. Unknown side effects stay conservative (never NONE).
 */
@ExtendWith(MockitoExtension.class)
class McpToolCapabilityProviderTest {

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private McpDiscoveryService discoveryService;
    @Mock
    private McpConnectionRuntime connectionRuntime;

    private McpToolCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        provider = new McpToolCapabilityProvider(connectionRepository,
                discoveryService, connectionRuntime);
    }

    private Connection connection(UUID id, String connectionId,
                                  ConnectionStatus status, boolean enabled) {
        return new Connection(id, connectionId, "n-" + connectionId,
                ConnectionKind.CUSTOM_MCP, status, enabled,
                Map.of("serverUrl", "https://mcp.example/" + connectionId),
                null, null, Instant.now(), Instant.now());
    }

    private McpDiscovery discovery(String... toolNames) {
        List<McpTool> tools = java.util.Arrays.stream(toolNames)
                .map(name -> new McpTool(name, "desc " + name,
                        Map.of("type", "object"), Map.of()))
                .toList();
        return new McpDiscovery("srv", "2025-06-18", tools, List.of(), List.of());
    }

    @Test
    void enabledConnectionExposesOneDescriptorPerTool() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-one",
                ConnectionStatus.CONNECTED, true);
        when(connectionRepository.list()).thenReturn(List.of(connection));
        when(discoveryService.discover(any())).thenReturn(
                discovery("alpha", "beta"));

        Collection<CapabilityDescriptor> descriptors =
                provider.descriptorsFor(CapabilityQueryContext.empty());

        assertThat(descriptors).extracting(CapabilityDescriptor::capabilityId)
                .containsExactlyInAnyOrder("mcp.conn-one.alpha", "mcp.conn-one.beta");
    }

    @Test
    void disabledConnectionExposesNothing() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-off",
                ConnectionStatus.CONNECTED, false);
        when(connectionRepository.list()).thenReturn(List.of(connection));

        assertThat(provider.descriptorsFor(CapabilityQueryContext.empty())).isEmpty();
    }

    @Test
    void failedConnectionExposesNothing() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-bad",
                ConnectionStatus.FAILED, true);
        when(connectionRepository.list()).thenReturn(List.of(connection));

        assertThat(provider.descriptorsFor(CapabilityQueryContext.empty())).isEmpty();
    }

    @Test
    void unknownSideEffectDefaultsConservatively() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-x",
                ConnectionStatus.CONNECTED, true);
        when(connectionRepository.list()).thenReturn(List.of(connection));
        when(discoveryService.discover(any())).thenReturn(discovery("mystery"));

        CapabilityDescriptor descriptor = provider.descriptorsFor(
                CapabilityQueryContext.empty()).iterator().next();
        assertThat(descriptor.sideEffectClass())
                .isNotEqualTo(SideEffectClass.NONE);
        assertThat(descriptor.readOnly()).isFalse();
    }

    @Test
    void readOnlyHintKeepsNoneSideEffect() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-ro",
                ConnectionStatus.CONNECTED, true);
        when(connectionRepository.list()).thenReturn(List.of(connection));
        McpDiscovery annotated = new McpDiscovery("srv", "v",
                List.of(new McpTool("reader", "read-only tool",
                        Map.of("type", "object"), Map.of("readOnlyHint", true))),
                List.of(), List.of());
        when(discoveryService.discover(any())).thenReturn(annotated);

        CapabilityDescriptor descriptor = provider.descriptorsFor(
                CapabilityQueryContext.empty()).iterator().next();
        assertThat(descriptor.sideEffectClass()).isEqualTo(SideEffectClass.NONE);
        assertThat(descriptor.readOnly()).isTrue();
    }

    @Test
    void faultedDiscoveryHidesConnectionRatherThanFailing() {
        UUID id = UUID.randomUUID();
        Connection connection = connection(id, "conn-flaky",
                ConnectionStatus.CONNECTED, true);
        when(connectionRepository.list()).thenReturn(List.of(connection));
        when(discoveryService.discover(any()))
                .thenThrow(new RuntimeException("boom"));

        assertThat(provider.descriptorsFor(CapabilityQueryContext.empty())).isEmpty();
    }

    @Test
    void descriptorForHonorsAvailability() {
        UUID id = UUID.randomUUID();
        Connection disabled = connection(id, "conn-d",
                ConnectionStatus.CONNECTED, false);
        when(connectionRepository.findById("conn-d"))
                .thenReturn(Optional.of(disabled));

        assertThat(provider.descriptorFor("mcp.conn-d.alpha")).isEmpty();
        assertThat(provider.canHandle("mcp.conn-d.alpha")).isTrue();
        assertThat(provider.canHandle("resource.extract_text")).isFalse();
    }
}
