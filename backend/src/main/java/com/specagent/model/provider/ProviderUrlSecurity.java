package com.specagent.model.provider;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 文件名:ProviderUrlSecurity.java
 *
 * 用途:OpenRouter(固定 base)与 Custom 共用的规范化 URL 策略。
 *
 * Base URL 指向 API 版本根(通常以 {@code /v1} 结尾)。适配器只追加一个规范
 * 后缀,绝不会再补第二个 {@code /v1}。校验采用 fail-closed 策略,且不会泄漏
 * 凭据。
 */
public final class ProviderUrlSecurity {

    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_MODEL_ID_LENGTH = 256;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http");

    private ProviderUrlSecurity() {
    }

    /** 去首尾空白、去掉末尾斜杠、限制长度,返回规范化的 base。 */
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

    /** 完整的 fail-closed 校验,包括 DNS 解析后的地址检查。 */
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
            // 允许 URI 可能当作 authority 处理的显式 loopback 字面量。
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
        // 危险的字面量地址一律拒绝。
        rejectDangerousLiteral(lowerHost);
        // 对非字面量主机做 DNS 解析后的检查。解析失败的主机在 URL 语法层面仍然
        // 合法;可达性由连接/探测阶段暴露。这里只拒绝确定危险的解析结果。
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
                // 离线 / 主机未知:语法层面放行,探测阶段再失败。
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

    /** 规范端点:规范化 base + 恰好一个适配器后缀。 */
    public static String canonicalEndpoint(String normalizedBaseUrl, CustomApiFormat format) {
        return normalizedBaseUrl + format.endpointSuffix();
    }

    /**
     * 规范化来源比较:scheme + host(小写)+ 有效端口(http 默认 80,
     * https 默认 443)。已存凭据只允许在同一来源上复用;两个 base URL
     * 归一到不同来源时,任何一方都不得静默携带对方的密钥。
     * 解析失败的输入一律按"不同来源"处理(保守方向)。
     */
    public static boolean sameOrigin(String baseUrlA, String baseUrlB) {
        String a = originOf(baseUrlA);
        return !a.isEmpty() && a.equals(originOf(baseUrlB));
    }

    /** scheme://host:有效端口 形式的来源;输入不合法时返回空串。 */
    public static String originOf(String baseUrl) {
        String normalized;
        URI uri;
        try {
            normalized = normalizeBaseUrl(baseUrl);
            uri = new URI(normalized);
        } catch (Exception ex) {
            return "";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (scheme.isEmpty() || host == null || host.isBlank()) {
            return "";
        }
        int port = uri.getPort();
        if (port == -1) {
            port = switch (scheme) {
                case "https" -> 443;
                case "http" -> 80;
                default -> -1;
            };
        }
        if (port == -1) {
            return "";
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
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
        // 169.254.0.0/16 链路本地 / 云厂商元数据地址。
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
