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
 * 文件名:GitTransportProxy.java
 *
 * 用途:Skill git 导入的出站路由决策。
 *
 * JGit 通过 JVM 默认的 {@link ProxySelector} 走 HTTPS,而 JVM 默认直连,
 * 除非宿主以 {@code -Djava.net.useSystemProxies=true} 或显式
 * {@code https.proxyHost} 属性启动。这正是为什么"浏览器"能通过本地代理访问
 * git 主机的机器,导入却仍然以裸 {@code TransportException} 失败:浏览器走
 * 操作系统代理,JVM 不走。
 *
 * 默认 {@code AUTO} 模式的解析顺序与浏览器环境的行为一致:显式代理环境
 * 变量优先,其次 Windows 系统代理(浏览器自己用的那个,从用户级 Internet
 * Settings 注册表键读取),然后是常见本地端口的在听代理(Clash 7897/7890、
 * v2ray 10809、SOCKS 1080 等),最后直连。{@code DIRECT} 强制绕过任何代理,
 * {@code host:port} 则固定某个代理。这里没有任何东西放松导入安全姿态 ——
 * 它只决定同一份已校验的 clone 走哪个 socket。
 */
public final class GitTransportProxy {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String PROPERTY_NAME = "spec.agent.skill.git.proxy";

    /** 在回退到本地探测之前,优先读取的代理环境变量。 */
    private static final List<String> PROXY_ENV_VARS = List.of(
            "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy");

    /** 未设置环境代理时按序探测的本地端口。 */
    private static final List<Integer> AUTO_LOCAL_PORTS = List.of(7897, 7890, 10809, 1080);

    /** 存放浏览器(WinINET)用户级代理的注册表键。 */
    private static final String WINDOWS_INTERNET_SETTINGS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    private static final int PROBE_TIMEOUT_MS = 250;

    private static final Object DEFAULT_SELECTOR_LOCK = new Object();

    /** 解析出的路由,附带可安全记录与展示在失败信息中的描述。 */
    public record Route(ProxySelector selector, String description) {
    }

    private GitTransportProxy() {
    }

    /**
     * 为一次导入解析路由。
     *
     * @param mode         {@code AUTO}(默认)、{@code DIRECT} 或 {@code host:port}
     * @param env          用于读取代理变量的环境
     * @param portOpen     用于探测本地在听代理的探针
     * @param systemProxy  返回操作系统/浏览器代理({@code host:port} 格式)的
     *                     供应商,未配置时返回 null(真实实现读取 Windows
     *                     Internet Settings 注册表;测试可注入)
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

    /** 使用进程环境与真实 TCP 探针解析。 */
    public static Route resolve(String mode) {
        return resolve(mode, System.getenv(), GitTransportProxy::isLocalPortOpen,
                GitTransportProxy::windowsSystemProxy);
    }

    /** 为既有调用方与测试保留的三参重载(不读系统代理)。 */
    public static Route resolve(String mode, Map<String, String> env, IntPredicate portOpen) {
        return resolve(mode, env, portOpen, () -> null);
    }

    /**
     * 通过 {@code reg query} 从 {@code HKCU\...\Internet Settings} 读取用户级
     * Windows 代理 —— 即浏览器实际使用的那个设置。ProxyEnable 开启时返回
     * {@code host:port},否则返回 null。任何失败(非 Windows、键缺失、超时、
     * 值无法解析)都是安全的:只是落入 AUTO 的下一步。
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
     * 从一份 {@code reg query} 输出中提取代理。是否启用由 {@code ProxyEnable}
     * 为 1 判定;{@code ProxyServer} 既可能是裸 {@code host:port},也可能是
     * 按协议的 {@code http=...;https=...} 列表(https 优先,其次 http)。
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
                // https 立即胜出;http 仅作回退。
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
     * 安装路由的 selector 后运行 {@code action},结束后恢复先前的 JVM 默认值。
     * 默认 selector 是进程级全局状态,因此安装动作串行化。
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

    /** 解析 {@code host:port},容忍 scheme 前缀与末尾斜杠。 */
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
        // 不支持带凭据的代理:凭据将不得不存进配置文件。
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
