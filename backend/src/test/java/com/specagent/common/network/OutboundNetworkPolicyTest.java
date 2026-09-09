package com.specagent.common.network;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared outbound network policy: scheme whitelist, SSRF/private-host
 * blocking, cloud-metadata names, and redirect revalidation.
 */
class OutboundNetworkPolicyTest {

    private final OutboundNetworkPolicy policy = new OutboundNetworkPolicy();

    @Test
    void httpsPublicUrlsAreAllowed() {
        assertThatCode(() -> policy.validateOutboundUrl(
                "https://github.com/BangShou1st/spec-agent.git", 3))
                .doesNotThrowAnyException();
    }

    @Test
    void httpIsRejectedWithoutOptIn() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("http://example.com/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void ftpAndOtherSchemesAreRejected() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("ftp://example.com/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void localhostIsRejectedByDefault() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://localhost:8080/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("blocked");
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://127.0.0.1/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void privateRfc1918AddressesAreRejectedAfterDnsResolution() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://10.0.0.5/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("blocked");
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://192.168.1.1/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://172.16.0.1/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void cloudMetadataNamesAreBlockedRegardlessOfDns() {
        assertThatThrownBy(() -> policy.validateOutboundUrl(
                "https://metadata.google.internal/computeMetadata/v1/", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("blocked");
        assertThatThrownBy(() -> policy.validateOutboundUrl(
                "https://169.254.169.254/latest/meta-data/", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void linkLocal169254IsBlocked() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://169.254.0.1/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void redirectLocationIsRevalidatedAgainstPolicy() {
        assertThatThrownBy(() -> policy.validateRedirect("http://10.0.0.5/evil"))
                .isInstanceOf(OutboundPolicyViolationException.class);
        assertThatCode(() -> policy.validateRedirect("https://github.com/owner/repo"))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidRedirectLimitIsRejected() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://example.com", -1))
                .isInstanceOf(OutboundPolicyViolationException.class);
    }

    @Test
    void localhostHttpAllowedOnlyWhenOptedIn() {
        OutboundNetworkPolicy lenient = new OutboundNetworkPolicy(true);
        assertThatCode(() -> lenient.validateOutboundUrl("http://localhost:9000/mcp", 3))
                .doesNotThrowAnyException();
        assertThatCode(() -> lenient.validateOutboundUrl("http://127.0.0.1:9000/mcp", 3))
                .doesNotThrowAnyException();
    }
}