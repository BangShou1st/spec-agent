package com.specagent.common.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:OutboundNetworkPolicyTest.java
 *
 * 测试目标:验证共享的出站网络策略——协议白名单、SSRF/私网地址拦截、
 * 云元数据主机名拦截以及重定向后的重新校验。
 *
 * 所有依赖地址解析的用例都注入确定性的
 * {@link OutboundNetworkPolicy.HostResolver} 桩。策略判断的是"这个主机是否
 * 允许访问",不应依赖运行环境的公共 DNS 或出网状态:之前断言依赖真实公网
 * 主机的实时解析,使本类成为整个测试套件中唯一需要公网的用例,且在代码
 * 完全相同的情况下会在 CI 中出现结果翻转。规则本身未变、依然全部覆盖,
 * 固定的只是解析器。
 *
 * 字面量 IP 地址从不经过解析器(生产代码和这里都是),因此
 * 私网/链路本地/元数据地址的用例仍然走真实的地址分类逻辑。
 */
class OutboundNetworkPolicyTest {

    /** 专用于测试的保留 TLD;用来代表任意的公网主机名。 */
    private static final String PUBLIC_HOST = "public.example.test";
    private static final String PUBLIC_V4 = "140.82.112.3";
    private static final String PUBLIC_V6 = "2606:50c0:8000::153";

    private static final Map<String, String> RESOLVED_HOSTS = Map.of(
            PUBLIC_HOST, PUBLIC_V4,
            "localhost", "127.0.0.1");

    /**
     * 解析已映射的主机,字面量地址直接透传,其余名称一律报告无法解析——
     * 恰好覆盖策略需要区分的三种结果。
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

    /** DNS rebinding 形态:公网名称解析到私网地址。 */
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

    /** 重定向同样不能访问元数据端点。 */
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
