package com.specagent.connection;

import com.specagent.common.network.OutboundNetworkPolicy;
import com.specagent.mcp.config.McpProperties;
import com.specagent.mcp.transport.McpClientFactory;
import com.specagent.mcp.transport.McpTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP transport boundary contract (no network): outbound policy is enforced
 * before any handshake, empty URLs fail closed, and the localhost opt-in only
 * relaxes loopback for the MCP boundary.
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
        // The handshake itself will fail (nothing listens), but the failure
        // must be a handshake failure — not a policy rejection. That proves
        // the test-only opt-in relaxes loopback while keeping SSRF defense.
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
