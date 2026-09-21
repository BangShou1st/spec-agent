package com.specagent.common.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared outbound network policy: scheme whitelist, SSRF/private-host
 * blocking, cloud-metadata names, and redirect revalidation.
 *
 * <p>Every address-resolution-dependent case injects a deterministic
 * {@link OutboundNetworkPolicy.HostResolver} stub. The policy decides "would
 * this host be allowed", which must not depend on the runner's public DNS or
 * egress state: asserting against live resolution of a real public host made
 * this class the only place in the suite that needed the public internet, and
 * it flipped two cases green/red on byte-identical code in CI. The rules
 * themselves are unchanged and still all checked — only the resolver is fixed.
 *
 * <p>Literal addresses never consult the resolver at all (neither in
 * production nor here), so the private/link-local/metadata cases keep
 * exercising the real address classifier.
 */
class OutboundNetworkPolicyTest {

    /** Reserved-for-testing TLD; stands in for any public hostname. */
    private static final String PUBLIC_HOST = "public.example.test";
    private static final String PUBLIC_V4 = "140.82.112.3";
    private static final String PUBLIC_V6 = "2606:50c0:8000::153";

    private static final Map<String, String> RESOLVED_HOSTS = Map.of(
            PUBLIC_HOST, PUBLIC_V4,
            "localhost", "127.0.0.1");

    /**
     * Resolves the mapped hosts, passes literal addresses straight through, and
     * reports every other name as unresolvable — exactly the three outcomes the
     * policy has to tell apart.
     */
    private static OutboundNetworkPolicy policyResolving(Map<String, String> hosts) {
        return new OutboundNetworkPolicy(false, host -> {
            String mapped = hosts.get(host);
            if (mapped != null) {
                return new InetAddress[] { InetAddress.getByName(mapped) };
            }
            if (host.matches("[0-9a-fA-F:.]+")) {
                return new InetAddress[] { InetAddress.getByName(host) };
            }
            throw new UnknownHostException(host);
        });
    }

    private final OutboundNetworkPolicy policy = policyResolving(RESOLVED_HOSTS);

    @Test
    void httpsPublicUrlsAreAllowed() {
        assertThatCode(() -> policy.validateOutboundUrl(
                "https://" + PUBLIC_HOST + "/BangShou1st/spec-agent.git", 3))
                .doesNotThrowAnyException();
    }

    @Test
    void httpsPublicIpv6HostIsAllowed() {
        OutboundNetworkPolicy ipv6 = policyResolving(Map.of(PUBLIC_HOST, PUBLIC_V6));
        assertThatCode(() -> ipv6.validateOutboundUrl("https://" + PUBLIC_HOST + "/repo", 3))
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

    /** DNS-rebinding shape: a public name that resolves into private space. */
    @Test
    void publicNameResolvingToPrivateAddressIsRejected() {
        OutboundNetworkPolicy rebound = policyResolving(Map.of("rebind.example.test", "10.1.2.3"));
        assertThatThrownBy(() -> rebound.validateOutboundUrl("https://rebind.example.test/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void unresolvableHostIsRejected() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://nope.example.test/x", 3))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("did not resolve");
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
        assertThatCode(() -> policy.validateRedirect("https://" + PUBLIC_HOST + "/owner/repo"))
                .doesNotThrowAnyException();
    }

    /** A redirect must not reach the metadata endpoint either. */
    @Test
    void redirectToMetadataEndpointIsRejected() {
        assertThatThrownBy(() -> policy.validateRedirect(
                "https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void invalidRedirectLimitIsRejected() {
        assertThatThrownBy(() -> policy.validateOutboundUrl("https://" + PUBLIC_HOST, -1))
                .isInstanceOf(OutboundPolicyViolationException.class)
                .hasMessageContaining("redirect limit");
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
