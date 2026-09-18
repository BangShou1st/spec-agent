package com.specagent.skill.importing;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Route resolution for the Skill git import. The JVM default is direct, so a
 * host whose browser reaches git through a local proxy fails with a bare
 * TransportException; AUTO closes that gap without a code path that can
 * silently reroute a correctly working direct connection.
 */
class GitTransportProxyTest {

    private static final Map<String, String> NO_ENV = Map.of();
    private static final java.util.function.IntPredicate NO_LOCAL_PROXY = port -> false;

    @Test
    void autoPrefersAnExplicitProxyEnvironmentVariable() {
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO,
                Map.of("HTTPS_PROXY", "http://127.0.0.1:7897"),
                NO_LOCAL_PROXY);

        assertThat(route.selector()).isNotNull();
        assertThat(route.description()).contains("HTTPS_PROXY").contains("127.0.0.1:7897");
        assertThat(firstProxy(route)).isEqualTo(
                new InetSocketAddress("127.0.0.1", 7897));
    }

    @Test
    void autoFallsBackToAListeningLocalProxyThenDirect() {
        GitTransportProxy.Route detected = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, NO_ENV, port -> port == 7897);
        assertThat(detected.selector()).isNotNull();
        assertThat(detected.description()).contains("127.0.0.1:7897");

        GitTransportProxy.Route direct = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, NO_ENV, NO_LOCAL_PROXY);
        // A null selector means "leave the JVM default alone": no rerouting.
        assertThat(direct.selector()).isNull();
        assertThat(direct.description()).contains("direct");
    }

    @Test
    void autoPrefersTheWindowsSystemProxyBeforeLocalPortProbing() {
        // The system proxy is the browser's own setting, so it must win over
        // the well-known-port guesswork — a browser-configured proxy on an
        // unusual port still routes the clone.
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, NO_ENV, port -> port == 7897,
                () -> "127.0.0.1:7899");

        assertThat(route.selector()).isNotNull();
        assertThat(route.description()).contains("system proxy").contains("127.0.0.1:7899");
        assertThat(firstProxy(route)).isEqualTo(new InetSocketAddress("127.0.0.1", 7899));
    }

    @Test
    void anUnusableSystemProxyValueFallsThroughToPortProbing() {
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, NO_ENV, port -> port == 7890,
                () -> "garbage");

        assertThat(route.selector()).isNotNull();
        assertThat(route.description()).contains("127.0.0.1:7890");
    }

    @Test
    void parseRegistryProxyReadsEnabledBareAndPerProtocolValues() {
        String enabled = "    ProxyEnable    REG_DWORD    0x1\n"
                + "    ProxyServer    REG_SZ    127.0.0.1:7897\n";
        assertThat(GitTransportProxy.parseRegistryProxy(enabled)).isEqualTo("127.0.0.1:7897");

        String perProtocol = "    ProxyEnable    REG_SZ    0x1\n"
                + "    ProxyServer    REG_SZ    http=127.0.0.1:10809;https=127.0.0.1:10810;ftp=127.0.0.1:10811\n";
        assertThat(GitTransportProxy.parseRegistryProxy(perProtocol)).isEqualTo("127.0.0.1:10810");

        // Disabled proxy: never reports a route even with a server value present.
        String disabled = "    ProxyEnable    REG_DWORD    0x0\n"
                + "    ProxyServer    REG_SZ    127.0.0.1:7897\n";
        assertThat(GitTransportProxy.parseRegistryProxy(disabled)).isNull();
        assertThat(GitTransportProxy.parseRegistryProxy(null)).isNull();
        assertThat(GitTransportProxy.parseRegistryProxy("garbage output")).isNull();
    }

    @Test
    void blankModeBehavesLikeAuto() {
        assertThat(GitTransportProxy.resolve(null, NO_ENV, NO_LOCAL_PROXY).selector()).isNull();
        assertThat(GitTransportProxy.resolve("   ", NO_ENV, NO_LOCAL_PROXY).selector()).isNull();
        assertThat(GitTransportProxy.resolve("system", NO_ENV, NO_LOCAL_PROXY).selector()).isNull();
    }

    @Test
    void directModeYieldsAnExplicitNoProxySelector() {
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_DIRECT, Map.of("HTTPS_PROXY", "http://127.0.0.1:7897"),
                port -> true);

        assertThat(route.selector()).isNotNull();
        assertThat(route.description()).contains("DIRECT");
        assertThat(route.selector().select(URI.create("https://github.com")))
                .hasSize(1)
                .allSatisfy(proxy -> assertThat(proxy.type()).isEqualTo(Proxy.Type.DIRECT));
    }

    @Test
    void explicitHostPortWins() {
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                "127.0.0.1:7897", NO_ENV, NO_LOCAL_PROXY);

        assertThat(route.description()).isEqualTo("proxy 127.0.0.1:7897");
        assertThat(firstProxy(route)).isEqualTo(new InetSocketAddress("127.0.0.1", 7897));
    }

    @Test
    void schemePrefixedAndSlashFormattedProxiesAreAccepted() {
        assertThat(firstProxy(GitTransportProxy.resolve("http://proxy.corp:8080/", NO_ENV,
                NO_LOCAL_PROXY))).isEqualTo(new InetSocketAddress("proxy.corp", 8080));
        assertThat(firstProxy(GitTransportProxy.resolve(
                "https://proxy.corp:8080", NO_ENV, NO_LOCAL_PROXY)))
                .isEqualTo(new InetSocketAddress("proxy.corp", 8080));
    }

    @Test
    void unusableProxyValuesFailClosedWithThePropertyName() {
        for (String value : List.of("not-a-proxy", ":8080", "host:99999", "socks5://h:1080",
                "user:pass@h:8080")) {
            assertThatThrownBy(() -> GitTransportProxy.resolve(value, NO_ENV, NO_LOCAL_PROXY))
                    .as("value %s must be rejected", value)
                    .isInstanceOf(SkillImportException.class)
                    .hasMessageContaining(GitTransportProxy.PROPERTY_NAME);
        }
    }

    @Test
    void aProxyEnvironmentVariableThatIsUnparsableIsIgnoredNotFatal() {
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, Map.of("HTTPS_PROXY", "garbage"), port -> false);

        assertThat(route.selector()).isNull();
        assertThat(route.description()).contains("direct");
    }

    @Test
    void callWithRestoresTheProcessDefaultSelector() throws Exception {
        ProxySelector previous = ProxySelector.getDefault();
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                "127.0.0.1:7897", NO_ENV, NO_LOCAL_PROXY);

        String result = GitTransportProxy.callWith(route, () -> "ran");

        assertThat(result).isEqualTo("ran");
        assertThat(ProxySelector.getDefault()).isSameAs(previous);
    }

    @Test
    void callWithADirectRouteTouchesNothing() throws Exception {
        ProxySelector previous = ProxySelector.getDefault();
        GitTransportProxy.Route route = GitTransportProxy.resolve(
                GitTransportProxy.MODE_AUTO, NO_ENV, NO_LOCAL_PROXY);

        assertThat(GitTransportProxy.callWith(route, () -> ProxySelector.getDefault()))
                .isSameAs(previous);
    }

    private static java.net.InetSocketAddress firstProxy(GitTransportProxy.Route route) {
        Proxy proxy = route.selector().select(URI.create("https://github.com")).get(0);
        return (java.net.InetSocketAddress) proxy.address();
    }
}
