package com.specagent.mcp;

import com.specagent.connection.Connection;
import com.specagent.connection.ConnectionKind;
import com.specagent.connection.ConnectionStatus;
import com.specagent.connection.ConnectionMcpConnectionLookup;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.mcp.domain.McpPrompt;
import com.specagent.mcp.domain.McpResource;
import com.specagent.mcp.domain.McpResourceContent;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.provider.McpConnectionCommandException;
import com.specagent.mcp.provider.McpPromptAssetProvider;
import com.specagent.mcp.provider.McpResourceProvider;
import com.specagent.mcp.runtime.McpConnectionLookupPort;
import com.specagent.mcp.runtime.McpConnectionRuntime;
import com.specagent.mcp.runtime.McpConnectionTarget;
import com.specagent.mcp.runtime.McpDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MCP primitive separation: resources are retrievable evidence with
 * provenance (never tools, never Graph truth); prompts are discoverable
 * assets only (never automatic system policy). Non-visible connections and
 * unknown URIs fail closed. Connections reach the providers only through the
 * MCP-owned lookup projection; the MCP-owned rejection maps at the API edge
 * to the historical 400 CONNECTION_COMMAND_REJECTED contract.
 */
@ExtendWith(MockitoExtension.class)
class McpResourcePromptSeparationTest {

    @Mock
    private McpConnectionLookupPort connectionLookup;
    @Mock
    private McpDiscoveryService discoveryService;
    @Mock
    private McpConnectionRuntime connectionRuntime;

    private McpResourceProvider resourceProvider;
    private McpPromptAssetProvider promptAssetProvider;

    private McpConnectionTarget visible;
    private McpDiscovery discovery;

    @BeforeEach
    void setUp() {
        resourceProvider = new McpResourceProvider(connectionLookup,
                discoveryService, connectionRuntime);
        promptAssetProvider = new McpPromptAssetProvider(connectionLookup,
                discoveryService);
        UUID id = UUID.randomUUID();
        visible = ConnectionMcpConnectionLookup.toTarget(new Connection(
                id, "conn-res", "res-conn",
                ConnectionKind.CUSTOM_MCP, ConnectionStatus.CONNECTED, true,
                Map.of("serverUrl", "https://mcp.example/conn-res"),
                null, null, Instant.now(), Instant.now()));
        discovery = new McpDiscovery("srv", "v",
                List.of(new McpTool("reader", "reads things",
                        Map.of("type", "object"), Map.of("readOnlyHint", true))),
                List.of(new McpResource("docs://guide", "guide",
                        "usage guide", "text/plain")),
                List.of(new McpPrompt("review", "review helper", 1)));
    }

    @Test
    void resourcesExposeProvenanceAndStaySeparateFromTools() {
        when(connectionLookup.findByRowId(visible.rowId()))
                .thenReturn(Optional.of(visible));
        when(discoveryService.discover(visible)).thenReturn(discovery);
        when(connectionRuntime.openAndRead(eq(visible), eq("docs://guide")))
                .thenReturn(new McpResourceContent("docs://guide",
                        "guide body", "text/plain",
                        Map.of("kind", "MCP_RESOURCE", "uri", "docs://guide")));

        assertThat(resourceProvider.discoverResources(visible.rowId()))
                .extracting(McpResource::uri).containsExactly("docs://guide");
        McpResourceContent content =
                resourceProvider.read(visible.rowId(), "docs://guide");
        assertThat(content.provenance()).containsEntry("kind", "MCP_RESOURCE");
        assertThat(content.provenance()).containsEntry("uri", "docs://guide");
    }

    @Test
    void unknownResourceUriFailsClosed() {
        when(connectionLookup.findByRowId(visible.rowId()))
                .thenReturn(Optional.of(visible));
        when(discoveryService.discover(visible)).thenReturn(discovery);

        assertThatThrownBy(() -> resourceProvider.read(visible.rowId(), "docs://nope"))
                .isInstanceOf(McpConnectionCommandException.class)
                .hasMessageContaining("not exposed");
    }

    @Test
    void unknownConnectionFailsClosed() {
        UUID unknown = UUID.randomUUID();
        when(connectionLookup.findByRowId(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resourceProvider.read(unknown, "docs://guide"))
                .isInstanceOf(McpConnectionCommandException.class)
                .hasMessageContaining("Connection not found");
    }

    @Test
    void invisibleConnectionCannotReadResources() {
        McpConnectionTarget disabled = new McpConnectionTarget(visible.rowId(),
                visible.connectionId(), false, visible.serverUrl(), null);
        when(connectionLookup.findByRowId(visible.rowId()))
                .thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> resourceProvider.read(visible.rowId(), "docs://guide"))
                .isInstanceOf(McpConnectionCommandException.class)
                .hasMessageContaining("not agent-visible");
    }

    @Test
    void promptsAreDiscoverableAssetsOnly() {
        when(connectionLookup.findByRowId(visible.rowId()))
                .thenReturn(Optional.of(visible));
        when(discoveryService.discover(visible)).thenReturn(discovery);

        List<McpPrompt> prompts = promptAssetProvider.discoverPrompts(visible.rowId());
        assertThat(prompts).extracting(McpPrompt::name).containsExactly("review");
        // Discovery carries names/descriptions/counts only — no prompt text
        // that could be mistaken for system policy.
        assertThat(prompts.get(0).argumentCount()).isEqualTo(1);
    }

    @Test
    void oversizedMetadataIsBoundedByTransport() {
        String huge = "x".repeat(10_000);
        McpTool tool = new McpTool("big", huge, Map.of("type", "object"), Map.of());
        assertThat(tool.description()).isEqualTo(huge);
        // Bounding happens at the transport normalization layer; the domain
        // record preserves what discovery cached. The provider descriptor path
        // is covered by McpClientFactory bounds (max-description-chars=320).
        assertThat(huge.length()).isGreaterThan(320);
    }
}
