package com.specagent.connection;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import com.specagent.connection.Connection;
import com.specagent.connection.ConnectionKind;
import com.specagent.connection.ConnectionLifecycleService;
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
 * 文件名:ConnectionMcpIntegrationTest.java
 *
 * 测试目标:针对真实 Postgres 存储与 SDK 实现的进程内 fake MCP 服务端
 * (与客户端同一套协议实现)验证连接 + MCP 运行时集成——生命周期、动态
 * 工具能力、只读调用、保守的副作用默认值、禁用即隐藏能力、以及凭据保密。
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
        // 指向 SDK 传输端点(/fake-mcp/* 映射 + /fake-mcp/mcp 端点);
        // URL 以客户端保存的为准。
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
        // 连接处于 CONNECTED 但从未 enable,因此它的 MCP 工具不能出现——
        // 无关的静态能力仍可列出。
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
                .isInstanceOf(com.specagent.connection.ConnectionCommandException.class);
        Connection failed = lifecycleService.findByRowId(connection.id()).orElseThrow();
        assertThat(failed.status().code()).isEqualTo("FAILED");
    }

    /**
     * Issue #14 connection/MCP 回归:一次完整生命周期在每一步都保持 MCP
     * 自有的发现缓存与凭据存储一致——create、test/discovery 写入缓存、
     * enable、更新密钥使缓存失效并轮换凭据、refresh 重新发现并重新缓存、
     * delete 清除两行。缓存表归 MCP 所有;connections 表归连接模块所有。
     */
    @Test
    void fullLifecycleKeepsDiscoveryCacheAndCredentialsConsistent() {
        Connection created = lifecycleService.create(ConnectionKind.CUSTOM_MCP,
                "fake-mcp-lifecycle", Map.of("serverUrl", fakeServerUrl),
                "lifecycle-secret-1");
        assertThat(created.status().code()).isEqualTo("CREATED");
        assertThat(cacheRows()).isZero();
        assertThat(credentialRows()).isEqualTo(1);
        String firstCredentialRef = created.credentialRef();

        // test/discovery 成功 -> 写入缓存
        McpDiscovery testedDiscovery = lifecycleService.test(created.id());
        assertThat(testedDiscovery.tools()).hasSize(1);
        assertThat(cacheRows()).isEqualTo(1);
        assertThat(lifecycleService.findByRowId(created.id()).orElseThrow()
                .status().code()).isEqualTo("TESTED");

        lifecycleService.connect(created.id());
        lifecycleService.enable(created.id());
        assertThat(lifecycleService.findByRowId(created.id()).orElseThrow()
                .enabled()).isTrue();
        assertThat(cacheRows()).isEqualTo(1);

        // 更新 config+secret -> 缓存失效、生命周期重置、
        // 旧凭据行被轮换掉
        Connection updated = lifecycleService.updateByConnectionId(
                created.connectionId(), null,
                Map.of("serverUrl", fakeServerUrl + "?v=2"),
                false, true, true, "lifecycle-secret-2");
        assertThat(updated.status().code()).isEqualTo("CREATED");
        assertThat(updated.enabled()).isFalse();
        assertThat(cacheRows()).isZero();
        assertThat(credentialRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM connection_credentials WHERE ref = ?",
                Integer.class, firstCredentialRef)).isZero();
        assertThat(updated.credentialRef()).isNotEqualTo(firstCredentialRef);
        assertThat(updated.credentialRef()).doesNotContain("lifecycle-secret-2");

        // refresh -> 重新实时发现,再次写入缓存
        lifecycleService.refresh(updated.id());
        assertThat(cacheRows()).isEqualTo(1);
        assertThat(lifecycleService.findByRowId(created.id()).orElseThrow()
                .status().code()).isEqualTo("CONNECTED");

        // delete -> 清除缓存与凭据
        lifecycleService.delete(updated.id());
        assertThat(cacheRows()).isZero();
        assertThat(credentialRows()).isZero();
        assertThat(lifecycleService.findByRowId(created.id())).isEmpty();
    }

    private int cacheRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mcp_discovery_cache", Integer.class);
    }

    private int credentialRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM connection_credentials", Integer.class);
    }
}