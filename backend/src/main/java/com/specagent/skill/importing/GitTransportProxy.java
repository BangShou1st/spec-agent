package com.specagent.skill.importing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Outbound route for the Skill git import.
 *
 * <p>JGit speaks HTTPS through the JVM's default {@link ProxySelector}, and the
 * JVM default is direct unless the host was started with
 * {@code -Djava.net.useSystemProxies=true} or explicit {@code https.proxyHost}
 * properties. That is why a machine whose <em>browser</em> reaches a git host
 * through a local proxy can still fail the import with a bare
 * {@code TransportException}: the browser uses the OS proxy, the JVM does not.
 *
 * <p>Resolution order for the default {@code AUTO} mode mirrors what the browser
 * environment already does: an explicit proxy environment variable wins, then
 * the Windows system proxy (the one the browser itself uses, read from the
 * per-user Internet Settings registry key), then a listening local proxy on a
 * well-known port (Clash 7897 / 7890, v2ray 10809, SOCKS 1080 and friends),
 * then direct. {@code DIRECT} force-bypasses any proxy, and {@code host:port}
 * pins one. Nothing here relaxes the import security posture — it only decides
 * which socket the same validated clone travels over.
 */
public final class GitTransportProxy {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String PROPERTY_NAME = "spec.agent.skill.git.proxy";

    /** Environment variables honoured before falling back to local probing. */
    private static final List<String> PROXY_ENV_VARS = List.of(
            "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy");

    /** Local ports probed in order when no environment proxy is set. */
    private static final List<Integer> AUTO_LOCAL_PORTS = List.of(7897, 7890, 10809, 1080);

    /** Registry key holding the per-user proxy the browser (WinINET) uses. */
    private static final String WINDOWS_INTERNET_SETTINGS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    private static final int PROBE_TIMEOUT_MS = 250;

    private static final Object DEFAULT_SELECTOR_LOCK = new Object();

    /** The resolved route plus a description safe to log and to show on failure. */
    public record Route(ProxySelector selector, String description) {
    }

    private GitTransportProxy() {
    }

    /**
     * Resolves the route for one import.
     *
     * @param mode         {@code AUTO} (default), {@code DIRECT}, or {@code host:port}
     * @param env          environment to read proxy variables from
     * @param portOpen     probe used to detect a listening local proxy
     * @param systemProxy  supplier returning the OS/browser proxy as {@code host:port},
     *                     or null when none is configured (real implementation reads the
     *                     Windows Internet Settings registry; injectable for tests)
     */
    public static Route resolve(String mode, Map<String, String> env, IntPredicate portOpen,
                                Supplier<String> systemProxy) {
        String normalized = mode == null ? "" : mode.strip();
        if (normalized.isEmpty() || MODE_AUTO.equalsIgnoreCase(normalized)
                || "SYSTEM".equalsIgnoreCase(normalized)) {
            for (String name : PROXY_ENV_VARS) {
                String value = env.get(name);
                if (value == null || value.isBlank()) {
                    continue;
                }
                InetSocketAddress address = parse(value);
                if (address != null) {
                    return new Route(ProxySelector.of(address), name + "=" + value.strip());
                }
            }
            String systemValue = systemProxy == null ? null : systemProxy.get();
            if (systemValue != null && !systemValue.isBlank()) {
                InetSocketAddress address = parse(systemValue);
                if (address != null) {
                    return new Route(ProxySelector.of(address),
                            "system proxy " + address.getHostString() + ":" + address.getPort());
                }
            }
            for (int port : AUTO_LOCAL_PORTS) {
                if (portOpen.test(port)) {
                    return new Route(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)),
                            "local proxy 127.0.0.1:" + port);
                }
            }
            return new Route(null, "direct (no proxy configured or detected)");
        }
        if (MODE_DIRECT.equalsIgnoreCase(normalized)) {
            return new Route(ProxySelector.of(null), "direct (" + MODE_DIRECT + ")");
        }
        InetSocketAddress address = parse(normalized);
        if (address == null) {
            throw new SkillImportException(PROPERTY_NAME + " must be " + MODE_DIRECT + ", "
                    + MODE_AUTO + " or host:port, but was: " + mode);
        }
        return new Route(ProxySelector.of(address), "proxy " + address.getHostString() + ":"
                + address.getPort());
    }

    /** Resolves with the process environment and a real TCP probe. */
    public static Route resolve(String mode) {
        return resolve(mode, System.getenv(), GitTransportProxy::isLocalPortOpen,
                GitTransportProxy::windowsSystemProxy);
    }

    /** Three-argument overload kept for existing callers and tests (no system proxy). */
    public static Route resolve(String mode, Map<String, String> env, IntPredicate portOpen) {
        return resolve(mode, env, portOpen, () -> null);
    }

    /**
     * Reads the per-user Windows proxy — the exact setting the browser uses —
     * from {@code HKCU\...\Internet Settings} via {@code reg query}. Returns
     * {@code host:port} when ProxyEnable is on, null otherwise. Every failure
     * (non-Windows, missing key, timeout, unparsable value) is fail-safe: it
     * just falls through to the next AUTO step.
     */
    static String windowsSystemProxy() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            return null;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("reg", "query", WINDOWS_INTERNET_SETTINGS_KEY);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.ISO_8859_1))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return parseRegistryProxy(output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Extracts the proxy from one {@code reg query} dump. Enabled is judged by
     * {@code ProxyEnable} being 1; {@code ProxyServer} is either a bare
     * {@code host:port} or a per-protocol {@code http=...;https=...} list where
     * the https (then http) entry wins.
     */
    static String parseRegistryProxy(String regQueryOutput) {
        if (regQueryOutput == null) {
            return null;
        }
        if (!java.util.regex.Pattern
                .compile("ProxyEnable\\s+REG_(?:SZ|DWORD)\\s+0x1\\b")
                .matcher(regQueryOutput).find()) {
            return null;
        }
        java.util.regex.Matcher server = java.util.regex.Pattern
                .compile("ProxyServer\\s+REG_SZ\\s+(\\S+)")
                .matcher(regQueryOutput);
        if (!server.find()) {
            return null;
        }
        String value = server.group(1);
        if (value.contains(";") || value.contains("=")) {
            String best = null;
            for (String entry : value.split(";")) {
                int equals = entry.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String protocol = entry.substring(0, equals).toLowerCase(Locale.ROOT);
                String address = entry.substring(equals + 1).strip();
                if (address.isEmpty()) {
                    continue;
                }
                // https wins immediately; http is only a fallback.
                if (protocol.equals("https")) {
                    return address;
                }
                if (protocol.equals("http") && best == null) {
                    best = address;
                }
            }
            return best;
        }
        return value;
    }

    /**
     * Runs {@code action} with the route's selector installed, restoring the
     * previous JVM default afterwards. The default selector is process-wide
     * state, so installs are serialized.
     */
    public static <T> T callWith(Route route, Callable<T> action) throws Exception {
        if (route.selector() == null) {
            return action.call();
        }
        synchronized (DEFAULT_SELECTOR_LOCK) {
            ProxySelector previous = ProxySelector.getDefault();
            ProxySelector.setDefault(route.selector());
            try {
                return action.call();
            } finally {
                ProxySelector.setDefault(previous);
            }
        }
    }

    /** Parses {@code host:port}, tolerating a scheme prefix and trailing slash. */
    static InetSocketAddress parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        int schemeSeparator = value.indexOf("://");
        if (schemeSeparator >= 0) {
            String scheme = value.substring(0, schemeSeparator).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            value = value.substring(schemeSeparator + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        // Credentials are not supported: they would have to be stored in config.
        if (value.contains("@")) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return null;
        }
        String host = value.substring(0, colon).strip();
        String portText = value.substring(colon + 1).strip();
        if (host.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new InetSocketAddress(host, port);
    }

    private static boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
