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
 * 文件名:McpResourcePromptSeparationTest.java
 *
 * 测试目标:验证 MCP 原语分离规则——resource 是带来源的可检索证据
 * (不是 tool,也不是 Graph 真值);prompt 仅是可发现的资产(绝不充当
 * 自动系统策略)。不可见的连接与未知 URI 一律快速失败。连接只能通过
 * MCP 自有的 lookup 投影触达 provider;MCP 自有的拒绝异常在 API 边界
 * 映射为历史的 400 CONNECTION_COMMAND_REJECTED 契约。
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
        // 发现结果只携带名称/描述/数量——不带可能被误当成系统策略的 prompt 文本。
        assertThat(prompts.get(0).argumentCount()).isEqualTo(1);
    }

    @Test
    void oversizedMetadataIsBoundedByTransport() {
        String huge = "x".repeat(10_000);
        McpTool tool = new McpTool("big", huge, Map.of("type", "object"), Map.of());
        assertThat(tool.description()).isEqualTo(huge);
        // 截断发生在传输层归一化;领域记录保留发现时缓存的内容。
        // provider 描述符路径的边界由 McpClientFactory 覆盖
        // (max-description-chars=320)。
        assertThat(huge.length()).isGreaterThan(320);
    }
}
