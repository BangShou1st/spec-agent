package com.specagent.connection;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.mcp.McpProperties;
import com.specagent.mcp.transport.McpClientFactory;
import com.specagent.mcp.transport.McpTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:McpClientFactoryPolicyTest.java
 *
 * 测试目标:验证 MCP 传输边界的契约(不依赖网络)——出站策略在任何
 * 握手之前强制执行、空 URL 快速失败、localhost 显式放行只对 MCP 边界
 * 放松回环限制。
 */
class McpClientFactoryPolicyTest {

    private McpClientFactory factory(boolean allowLocalhost) {
        McpProperties properties = new McpProperties();
        properties.setAllowLocalhostHttp(allowLocalhost);
        return new McpClientFactory(properties, new OutboundNetworkPolicy(false));
    }

    @Test
    void privateHostsAreRejectedWithoutOptIn() {
        assertThatThrownBy(() -> factory(false)
                .open("http://127.0.0.1:9999/fake-mcp/mcp", null, null))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("outbound policy");
    }

    @Test
    void localhostHttpPassesWithExplicitOptIn() {
        // 握手本身会失败(没有服务在监听),但失败必须是握手失败——
        // 而不是策略拒绝。这证明仅测试用的 opt-in 放松了回环限制,
        // 同时保留了 SSRF 防御。
        assertThatThrownBy(() -> factory(true)
                .open("http://127.0.0.1:9/fake-mcp/mcp", null, null))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("MCP handshake failed");
    }

    @Test
    void emptyServerUrlFailsClosed() {
        assertThatThrownBy(() -> factory(true).open("  ", null, null))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void cloudMetadataHostsStayBlockedEvenWithOptIn() {
        assertThatThrownBy(() -> factory(true)
                .open("http://169.254.169.254/latest/meta-data/", null, null))
                .isInstanceOf(McpTransportException.class)
                .hasMessageContaining("outbound policy");
    }
}
