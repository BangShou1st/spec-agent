package com.specagent.common.network;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Shared outbound network policy for server-initiated requests (Skill Git
 * import, custom MCP servers). One policy, not one ad-hoc URL check per
 * feature.
 *
 * <p>Enforcement focuses on SSRF and external-network safety:
 * <ul>
 *   <li>allowed schemes only ({@code https}, and {@code http} for localhost
 *       test/tooling where explicitly enabled);</li>
 *   <li>DNS-is-resolved and the target must not be a private/link-local/
 *       loopback/cloud-metadata address;</li>
 *   <li>redirects are re-validated against the same rules;</li>
 *   <li>timeouts and byte bounds are passed through as call parameters.</li>
 * </ul>
 */
@Component
public class OutboundNetworkPolicy {

    /** Addresses that must never be contacted by server-initiated requests. */
    public static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal", "metadata.google", "169.254.169.254",
            "100.100.100.200", // Alibaba cloud metadata
            "metadata", "metadata.azure.internal", "instance-data");

    private static final List<String> PRIVATE_PREFIXES = List.of(
            "10.", "192.168.", "100.64.", "169.254.", "172.");
    private static final String IPV6_LINK_LOCAL = "fe80";
    private static final String IPV6_ULA = "fc";

    private final boolean allowLocalhostHttp;

    public OutboundNetworkPolicy() {
        this(false);
    }

    public OutboundNetworkPolicy(boolean allowLocalhostHttp) {
        this.allowLocalhostHttp = allowLocalhostHttp;
    }

    /**
     * Validates a URL for outbound connection. Returns the URI when it may be
     * contacted; otherwise throws {@link OutboundPolicyViolationException}.
     */
    public URI validateOutboundUrl(String url, int maxRedirects) {
        URI uri = parse(url);
        validateScheme(uri);
        validateHost(uri.getHost());
        if (maxRedirects < 0 || maxRedirects > 10) {
            throw new OutboundPolicyViolationException("Invalid redirect limit: " + maxRedirects);
        }
        return uri;
    }

    /** Validates a redirect location against the same policy as the original. */
    public URI validateRedirect(String location) {
        URI uri = parse(location);
        validateScheme(uri);
        validateHost(uri.getHost());
        return uri;
    }

    public boolean isLocalhost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase();
        return lower.equals("localhost") || lower.equals("::1")
                || lower.startsWith("127.") || lower.equals("0:0:0:0:0:0:0:1");
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new OutboundPolicyViolationException("Empty outbound URL");
        }
        try {
            return URI.create(url.strip());
        } catch (IllegalArgumentException ex) {
            throw new OutboundPolicyViolationException("Malformed outbound URL: " + url);
        }
    }

    private void validateScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new OutboundPolicyViolationException("Outbound URL must declare a scheme");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLocalhost(uri) && allowLocalhostHttp) {
            return;
        }
        throw new OutboundPolicyViolationException(
                "Outbound URL scheme not allowed: " + scheme
                        + (isLocalhost(uri) ? " (localhost http requires explicit opt-in)" : ""));
    }

    private void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new OutboundPolicyViolationException("Outbound URL lacks a host");
        }
        String lower = host.toLowerCase();
        // Cloud metadata hostnames are always blocked by name regardless of DNS.
        for (String blocked : BLOCKED_HOSTS) {
            if (lower.equals(blocked) || lower.endsWith("." + blocked)) {
                throw new OutboundPolicyViolationException(
                        "Outbound host is blocked: " + host);
            }
        }
        // Explicit localhost opt-in (test/tooling hooks) bypasses the SSRF
        // address checks for loopback targets only.
        if (allowLocalhostHttp && (lower.equals("localhost")
                || lower.equals("::1") || lower.startsWith("127.")
                || lower.equals("0:0:0:0:0:0:0:1"))) {
            return;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            boolean anyResolved = false;
            for (InetAddress address : addresses) {
                anyResolved = true;
                if (isBlockedAddress(address)) {
                    throw new OutboundPolicyViolationException(
                            "Outbound host resolves to a blocked address: " + host
                                    + " -> " + address.getHostAddress());
                }
            }
            if (!anyResolved) {
                throw new OutboundPolicyViolationException(
                        "Outbound host did not resolve: " + host);
            }
        } catch (UnknownHostException ex) {
            throw new OutboundPolicyViolationException(
                    "Outbound host did not resolve: " + host);
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            String text = ipv4.getHostAddress();
            for (String prefix : PRIVATE_PREFIXES) {
                if (text.startsWith(prefix)) {
                    return true;
                }
            }
        } else if (address instanceof Inet6Address ipv6) {
            String text = ipv6.getHostAddress().toLowerCase();
            if (text.startsWith(IPV6_LINK_LOCAL) || text.startsWith(IPV6_ULA)) {
                return true;
            }
        }
        return false;
    }
}