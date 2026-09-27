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
 * 文件名:OutboundNetworkPolicy.java
 *
 * 用途:服务端主动发起的外呼请求(Skill 的 Git 导入、自定义 MCP 服务器等)
 * 共用的出网安全策略。所有功能走同一套策略,而不是各写各的 URL 检查。
 *
 * 防护重点在 SSRF 与外网安全:
 * - 只允许的 scheme({@code https};{@code http} 仅限 localhost 的
 *       测试/工具场景,且必须显式开启);
 * - 必须能完成 DNS 解析,且目标不得是私有/链路本地/回环/云元数据地址;
 * - 重定向地址按同样规则重新校验;
 * - 超时与字节上限由调用方作为参数传入。
 */
@Component
public class OutboundNetworkPolicy {

    /** 服务端主动发起的请求绝不允许访问的地址。 */
    public static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal", "metadata.google", "169.254.169.254",
            "100.100.100.200", // 阿里云元数据地址
            "metadata", "metadata.azure.internal", "instance-data");

    private static final List<String> PRIVATE_PREFIXES = List.of(
            "10.", "192.168.", "100.64.", "169.254.", "172.");
    private static final String IPV6_LINK_LOCAL = "fe80";
    private static final String IPV6_ULA = "fc";

    /**
     * 主机名解析接缝。
     *
     * 生产环境始终走 JVM 的解析器
     * ({@link InetAddress#getAllByName(String)})。测试注入确定性的桩,使
     * 基于地址的 SSRF 检查不必依赖真实公共 DNS 即可验证:本策略的契约是
     * "这个主机是否被允许",而不是"这台机器能否连通公共互联网"。
     */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLocalhostHttp;
    private final HostResolver hostResolver;

    public OutboundNetworkPolicy() {
        this(false, InetAddress::getAllByName);
    }

    public OutboundNetworkPolicy(boolean allowLocalhostHttp) {
        this(allowLocalhostHttp, InetAddress::getAllByName);
    }

    /** 仅供测试使用的接缝:生产代码使用 JVM 解析器(见上)。 */
    public OutboundNetworkPolicy(boolean allowLocalhostHttp, HostResolver hostResolver) {
        this.allowLocalhostHttp = allowLocalhostHttp;
        this.hostResolver = hostResolver;
    }

    /**
     * 校验一个 URL 是否允许外呼。允许访问时返回解析后的 URI,
     * 否则抛出 {@link OutboundPolicyViolationException}。
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

    /** 按与原地址相同的策略校验重定向目标。 */
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
        // 云元数据主机名不依赖 DNS 结果,一律按名称直接拦截。
        for (String blocked : BLOCKED_HOSTS) {
            if (lower.equals(blocked) || lower.endsWith("." + blocked)) {
                throw new OutboundPolicyViolationException(
                        "Outbound host is blocked: " + host);
            }
        }
        // 显式开启的 localhost 白名单(测试/工具钩子)只对回环地址
        // 跳过 SSRF 地址检查。
        if (allowLocalhostHttp && (lower.equals("localhost")
                || lower.equals("::1") || lower.startsWith("127.")
                || lower.equals("0:0:0:0:0:0:0:1"))) {
            return;
        }
        try {
            InetAddress[] addresses = hostResolver.resolve(host);
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