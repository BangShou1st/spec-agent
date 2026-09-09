package com.specagent.connection;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.connection.domain.Connection;
import com.specagent.connection.domain.ConnectionKind;
import com.specagent.connection.service.ConnectionLifecycleService;
import com.specagent.mcp.domain.McpDiscovery;
import com.specagent.support.SdkFakeMcpServerConfig;
import com.specagent.common.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Connection + MCP runtime integration against the real Postgres store and an
 * SDK-backed in-process fake MCP server (same protocol implementation as the
 * client): lifecycle, dynamic tool capabilities, read-only invocation,
 * conservative side-effect defaults, disabled-hides-capabilities, and
 * credential secrecy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SdkFakeMcpServerConfig.class)
@ActiveProfiles("test")
class ConnectionMcpIntegrationTest {

    @Autowired
    private ConnectionLifecycleService lifecycleService;
    @Autowired
    private CapabilityRegistry capabilityRegistry;
    @Autowired
    private SdkFakeMcpServerConfig.Fakes fakes;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int serverPort;

    private String fakeServerUrl;

    @BeforeEach
    void setUp() {
        // Points at the exact SDK transport endpoint (/fake-mcp/* mapping +
        // /fake-mcp/mcp endpoint); the client keeps the URL authoritative.
        fakeServerUrl = "http://127.0.0.1:" + serverPort + "/fake-mcp/mcp";
        fakes.calls().set(0);
        fakes.setFailToolCall(false);
        clearTables();
    }

    @AfterEach
    void cleanup() {
        clearTables();
    }

    private void clearTables() {
        jdbcTemplate.update("DELETE FROM mcp_discovery_cache");
        jdbcTemplate.update("DELETE FROM connection_credentials");
        jdbcTemplate.update("DELETE FROM connections");
    }

    @Test
    void fullConnectionLifecycleDiscoversAndDisappears() {
        Connection created = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp", Map.of("serverUrl", fakeServerUrl), "test-secret-value");
        assertThat(created.status().code()).isEqualTo("CREATED");
        assertThat(created.credentialRef()).isNotNull();
        assertThat(created.credentialRef()).doesNotContain("test-secret-value");
        assertThat(created.enabled()).isFalse();

        McpDiscovery discovery = lifecycleService.test(created.id());
        assertThat(discovery.tools()).hasSize(1);
        assertThat(discovery.tools().get(0).name()).isEqualTo(SdkFakeMcpServerConfig.TOOL_NAME);

        lifecycleService.connect(created.id());
        Connection connected = lifecycleService.findByRowId(created.id()).orElseThrow();
        assertThat(connected.status().code()).isEqualTo("CONNECTED");

        lifecycleService.enable(created.id());
        Collection<CapabilityDescriptor> descriptors =
                capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty());
        Optional<CapabilityDescriptor> tool = descriptors.stream()
                .filter(d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME))
                .findFirst();
        assertThat(tool).isPresent();
        assertThat(tool.get().inputSchema()).containsKey("properties");

        lifecycleService.disable(created.id());
        descriptors = capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty());
        assertThat(descriptors.stream().anyMatch(
                d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME)))
                .isFalse();
    }

    @Test
    void toolInvocationReturnsProvenanceBearingResult() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-b", Map.of("serverUrl", fakeServerUrl), null);
        lifecycleService.connect(connection.id());
        lifecycleService.enable(connection.id());

        String capabilityId = capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty())
                .stream().filter(d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME))
                .findFirst().orElseThrow().capabilityId();

        CapabilityResult result = capabilityRegistry.findAdapter(capabilityId).orElseThrow()
                .invoke(new CapabilityInvocation(
                        Ids.random(), "key-1", capabilityId,
                        UUID.randomUUID(), null, Map.of("query", "离线队列")));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(result.provenance()).containsEntry("kind", "MCP_TOOL");
        assertThat(result.provenance()).containsEntry("toolName", SdkFakeMcpServerConfig.TOOL_NAME);
        assertThat(fakes.calls().get()).isGreaterThan(0);
    }

    @Test
    void failedToolCallReturnsTypedFailure() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-c", Map.of("serverUrl", fakeServerUrl), null);
        lifecycleService.connect(connection.id());
        lifecycleService.enable(connection.id());
        fakes.setFailToolCall(true);

        String capabilityId = capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty())
                .stream().filter(d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME))
                .findFirst().orElseThrow().capabilityId();

        CapabilityResult result = capabilityRegistry.findAdapter(capabilityId).orElseThrow()
                .invoke(new CapabilityInvocation(
                        Ids.random(), "key-2", capabilityId,
                        UUID.randomUUID(), null, Map.of("query", "x")));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(result.content().containsKey("reason")).isTrue();
    }

    @Test
    void unknownMCPToolSideEffectDefaultsConservatively() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-d", Map.of("serverUrl", fakeServerUrl), null);
        lifecycleService.connect(connection.id());
        lifecycleService.enable(connection.id());

        CapabilityDescriptor descriptor = capabilityRegistry.descriptorsFor(
                CapabilityQueryContext.empty()).stream()
                .filter(d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME))
                .findFirst().orElseThrow();
        assertThat(descriptor.sideEffectClass()).isNotEqualTo(SideEffectClass.NONE);
        assertThat(descriptor.readOnly()).isFalse();
    }

    @Test
    void disabledConnectionIsInvisibleToPlannerCandidates() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-e", Map.of("serverUrl", fakeServerUrl), null);
        lifecycleService.connect(connection.id());
        // The connection is CONNECTED but never enabled, so its MCP tool must
        // not appear — while unrelated static capabilities may still be listed.
        assertThat(capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty())
                .stream().noneMatch(
                        d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME)))
                .isTrue();
    }

    @Test
    void secretNeverReachesDescriptorOrResult() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-f", Map.of("serverUrl", fakeServerUrl),
                "super-secret-token-abc");
        lifecycleService.connect(connection.id());
        lifecycleService.enable(connection.id());

        String capabilityId = capabilityRegistry.descriptorsFor(CapabilityQueryContext.empty())
                .stream().filter(d -> d.capabilityId().endsWith("." + SdkFakeMcpServerConfig.TOOL_NAME))
                .findFirst().orElseThrow().capabilityId();

        CapabilityResult result = capabilityRegistry.findAdapter(capabilityId).orElseThrow()
                .invoke(new CapabilityInvocation(
                        Ids.random(), "key-3", capabilityId,
                        UUID.randomUUID(), null, Map.of()));
        String all = capabilityId + "|" + result.provenance() + "|" + result.content();
        assertThat(all).doesNotContain("super-secret-token-abc");
    }

    @Test
    void deleteRemovesConnectionAndCredentials() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-g", Map.of("serverUrl", fakeServerUrl), "secret-for-delete");
        lifecycleService.connect(connection.id());

        lifecycleService.delete(connection.id());

        assertThat(lifecycleService.findByRowId(connection.id())).isEmpty();
        Integer credRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM connection_credentials WHERE connection_id = ?",
                Integer.class, connection.id());
        assertThat(credRows).isZero();
    }

    @Test
    void connectionFailsWhenServerRejectsHandshake() {
        Connection connection = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-h", Map.of("serverUrl", "http://127.0.0.1:" + serverPort
                        + "/not-a-mcp-endpoint"), null);

        assertThatThrownBy(() -> lifecycleService.test(connection.id()))
                .isInstanceOf(com.specagent.connection.service.ConnectionCommandException.class);
        Connection failed = lifecycleService.findByRowId(connection.id()).orElseThrow();
        assertThat(failed.status().code()).isEqualTo("FAILED");
    }
}