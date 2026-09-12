package com.specagent.model.provider;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical URL policy shared by OpenRouter (fixed base) and Custom.
 *
 * <p>Base URL denotes the API version root (usually ending in {@code /v1}).
 * Adapters append exactly one canonical suffix; no second {@code /v1} is
 * ever added. Validation is fail-closed and credential-safe.
 */
public final class ProviderUrlSecurity {

    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_MODEL_ID_LENGTH = 256;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    private ProviderUrlSecurity() {
    }

    /** Trim, strip trailing slashes, enforce length. Returns normalized base. */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("API Base URL is required");
        }
        String v = raw.trim();
        if (v.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("API Base URL is too long");
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.isEmpty()) {
            throw new IllegalArgumentException("API Base URL is required");
        }
        return v;
    }

    /** Full fail-closed validation including DNS-resolved address checks. */
    public static String validateAndNormalizeBaseUrl(String raw) {
        String normalized = normalizeBaseUrl(raw);
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("API Base URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Only https and loopback http are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("API Base URL must not contain credentials");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("API Base URL must not contain query or fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Allow explicit loopback literals that URI may treat as authority.
            String authority = uri.getAuthority();
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException("API Base URL must contain a host");
            }
            host = authority;
            int at = host.lastIndexOf('@');
            if (at >= 0) {
                throw new IllegalArgumentException("API Base URL must not contain credentials");
            }
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
        }
        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("API Base URL host is invalid");
        }
        String lowerHost = asciiHost.toLowerCase(Locale.ROOT);
        boolean loopback = isLoopbackHost(lowerHost);
        if ("http".equals(scheme) && !loopback) {
            throw new IllegalArgumentException("http is only allowed for localhost / loopback");
        }
        // Dangerous literal destinations are always rejected.
        rejectDangerousLiteral(lowerHost);
        // DNS-resolved check for non-literal hosts. Unresolvable hosts stay
        // valid at URL-syntax time; connection/probe surfaces reachability.
        // Only a positively-dangerous resolution is rejected here.
        if (!isIpLiteral(lowerHost)) {
            try {
                InetAddress[] resolved = InetAddress.getAllByName(asciiHost);
                for (InetAddress addr : resolved) {
                    if (isDangerousAddress(addr)) {
                        throw new IllegalArgumentException("API Base URL resolves to a blocked address");
                    }
                }
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (java.net.UnknownHostException ex) {
                // Offline / unknown host: allow syntax, fail at probe time.
            } catch (Exception ex) {
                throw new IllegalArgumentException("API Base URL host is invalid");
            }
        } else {
            try {
                InetAddress addr = InetAddress.getByName(stripBrackets(lowerHost));
                if (isDangerousAddress(addr)) {
                    throw new IllegalArgumentException("API Base URL is a blocked address");
                }
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("API Base URL host is invalid");
            }
        }
        return normalized;
    }

    /** Canonical endpoint: normalized base + exactly one adapter suffix. */
    public static String canonicalEndpoint(String normalizedBaseUrl, CustomApiFormat format) {
        return normalizedBaseUrl + format.endpointSuffix();
    }

    public static String normalizeModelId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Model is required");
        }
        String v = raw.trim();
        if (v.length() > MAX_MODEL_ID_LENGTH) {
            throw new IllegalArgumentException("Model id is too long");
        }
        return v;
    }

    private static boolean isLoopbackHost(String lowerHost) {
        String h = stripBrackets(lowerHost);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
    }

    private static boolean isIpLiteral(String lowerHost) {
        String h = stripBrackets(lowerHost);
        if (h.contains(":")) {
            return true;
        }
        return h.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    private static String stripBrackets(String h) {
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    private static void rejectDangerousLiteral(String lowerHost) {
        String h = stripBrackets(lowerHost);
        if (h.equals("169.254.169.254")) {
            throw new IllegalArgumentException("API Base URL is a blocked address");
        }
        if (h.equals("0.0.0.0") || h.equals("::") || h.equals("::ffff:0.0.0.0")) {
            throw new IllegalArgumentException("API Base URL is a blocked address");
        }
    }

    private static boolean isDangerousAddress(InetAddress addr) {
        if (addr.isLinkLocalAddress() || addr.isMulticastAddress() || addr.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        // 169.254.0.0/16 link-local / cloud metadata.
        if (raw.length == 4 && (raw[0] & 0xFF) == 169 && (raw[1] & 0xFF) == 254) {
            return true;
        }
        String host = addr.getHostAddress();
        if (host != null && host.equals("169.254.169.254")) {
            return true;
        }
        return false;
    }
}
