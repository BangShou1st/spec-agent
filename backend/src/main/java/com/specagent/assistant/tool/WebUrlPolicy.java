package com.specagent.assistant.tool;

import java.net.*;
import java.util.Locale;

/** Public HTTP(S) targets only, including provider-returned canonical URLs. */
public final class WebUrlPolicy {
    private WebUrlPolicy() {}
    public static String requirePublic(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (value.length() > 2000 || !java.util.Set.of("http", "https").contains(uri.getScheme())
                    || uri.getRawUserInfo() != null || host == null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                    || lower.endsWith(".internal") || lower.endsWith(".") || !lower.contains("."))
                throw new IllegalArgumentException();
            for (InetAddress address : InetAddress.getAllByName(host)) if (!isPublic(address)) throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception invalid) { throw new IllegalArgumentException("WEB_URL_NOT_PUBLIC"); }
    }
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 0xe0) == 0x20; // global unicast only
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && (b == 168 || b == 0))
                && !(a == 198 && (b == 18 || b == 19));
    }
}
