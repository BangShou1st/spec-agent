package com.specagent.common.security;

import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文件名:LoopbackBindingGuard.java
 *
 * 用途:交付形态的访问边界守卫。三层防线:
 *
 * 1. 配置守卫(Bean 构造期,即上下文刷新的单例实例化阶段):Spring Boot
 *    在所有单例 Bean 创建完成后才启动内嵌 Web 服务器,因此构造期抛出
 *    异常必然发生在监听之前——这是"监听前拒绝非法配置"的可靠生命周期
 *    挂点。{@code server.address} 为空/空白不是"显式回环"——嵌入式服务器
 *    会把空地址解释为通配监听,因此拒绝启动。
 * 2. <b>认证模式守卫(第二阶段新增)</b>:非回环绑定要求实例自带认证层。
 *    回环绑定原本同时承担了"网络隔离"和"事实上的访问控制"两个角色;
 *    一旦改绑到网络地址,第二个角色就消失了。因此:非回环 + LOCAL 模式
 *    <b>即使显式声明知情也不放行</b>——LOCAL 模式按设计仅限回环。要对外
 *    提供服务就必须切到 OIDC(自带登录),而不是靠一个布尔开关。
 * 3. 实际绑定校验({@link WebServerInitializedEvent}):服务器启动后读取
 *    <strong>每一个</strong> Tomcat 连接器真实生效的监听地址,再次确认
 *    回环——配置字符串与实际绑定之间的差异(环境变量、命令行覆盖、
 *    定制器等)不会静默放行。校验用项目 Tomcat 真实支持的
 *    {@link Connector#getProperty(String)} API;地址读取不到或为空一律
 *    视为通配绑定并 fail closed,绝不把"读取失败"或"地址未知"当作安全回环。
 *
 * 注意本类刻意只依赖 {@code spec.agent.security.mode} 的<b>字符串</b>,
 * 不 import {@code com.specagent.access}:common 与 access 同属运行时内核,
 * 相互依赖会构成模块级循环并触发 ArchUnit 无环门禁。
 */
@Component
public class LoopbackBindingGuard {

    private final String serverAddress;
    private final boolean allowNonLoopback;
    private final String securityMode;

    public LoopbackBindingGuard(
            @Value("${server.address:}") String serverAddress,
            @Value("${spec.agent.security.allow-non-loopback:${SPEC_AGENT_ALLOW_NON_LOOPBACK:false}}")
            boolean allowNonLoopback,
            @Value("${spec.agent.security.mode:LOCAL}") String securityMode) {
        this.serverAddress = serverAddress;
        this.allowNonLoopback = allowNonLoopback;
        this.securityMode = securityMode;
        validateConfiguration(serverAddress, allowNonLoopback, securityMode);
    }

    /** 配置层守卫;构造期调用,拒绝空地址与未声明认证的非回环绑定。 */
    void validateConfiguration(String address, boolean allowNonLoopback, String mode) {
        // 空/空白地址不是显式回环:嵌入式服务器会退化为通配监听,
        // 把产品 API 暴露到网络。必须在监听开始前拒绝。
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "server.address is empty: the embedded server would listen on all "
                            + "interfaces while the product API has no authentication boundary. "
                            + "Set server.address=127.0.0.1 (the delivery default) or, for a "
                            + "deliberate network deployment, run in OIDC mode with "
                            + "SPEC_AGENT_ALLOW_NON_LOOPBACK=true.");
        }
        if (isLoopback(address)) {
            return;
        }
        if (allowNonLoopback) {
            // 明确知情的网络部署:对外的登录边界必须由本实例自带的认证层
            // 承担,部署者仍应提供反向代理/防火墙加固。
            requireAuthenticatingMode(address, mode);
            return;
        }
        throw new IllegalStateException(
                "server.address is set to a non-loopback address (" + address
                        + ") but the product API has no authentication boundary. "
                        + "For the supported single-user delivery keep server.address=127.0.0.1 "
                        + "(default). For a deliberate network deployment set "
                        + "SPEC_AGENT_ALLOW_NON_LOOPBACK=true, switch to OIDC mode and provide "
                        + "external authentication and isolation.");
    }

    /**
     * 非回环绑定必须自带认证层。LOCAL 模式按设计仅限回环,显式"知情开关"
     * 也不豁免——一个布尔开关不能替代登录。
     */
    private void requireAuthenticatingMode(String address, String mode) {
        if (mode != null && mode.trim().equalsIgnoreCase("OIDC")) {
            return;
        }
        throw new IllegalStateException(
                "Refusing a non-loopback address (" + address + "): binding a non-loopback "
                        + "address drops the loopback isolation that currently stands in for "
                        + "access control, and mode=" + (mode == null ? "(unset)" : mode)
                        + " provides no authentication of its own. LOCAL mode is loopback-only "
                        + "by design; switch to OIDC mode (spec.agent.security.mode=OIDC) before "
                        + "serving a network address.");
    }

    /**
     * 服务器初始化后校验实际生效的监听地址。逐个连接器读取真实绑定地址:
     * 非回环、或读取不到/为空(通配绑定)时启动失败。事件在服务器开始监听后
     * 发布,抛出异常会使应用启动失败退出,不会继续对外提供服务。
     */
    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        List<String> addresses = actualBindAddresses(event);
        for (String actual : addresses) {
            if (isLoopback(actual)) {
                continue;
            }
            if (allowNonLoopback) {
                requireAuthenticatingMode(actual, securityMode);
                continue;
            }
            throw new IllegalStateException(
                    "The web server is actually bound to a non-loopback address (" + actual
                            + ") while no network deployment was declared. Refusing to serve "
                            + "an unauthenticated product API beyond loopback.");
        }
    }

    /**
     * 全部 Tomcat 连接器上真实生效的监听地址。任何连接器地址读取不到、
     * 为空或不是 Tomcat 服务器都直接抛出异常(fail closed):这些情况意味着
     * 实际绑定不可验证,而不可验证绝不等于安全的回环绑定。
     */
    List<String> actualBindAddresses(WebServerInitializedEvent event) {
        if (!(event.getWebServer() instanceof TomcatWebServer tomcatWebServer)) {
            throw new IllegalStateException(
                    "LoopbackBindingGuard could not verify the actual bind address: the "
                            + "web server is not Tomcat ("
                            + event.getWebServer().getClass().getName()
                            + "). The product delivery requires a verifiable loopback bind.");
        }
        Tomcat tomcat = tomcatWebServer.getTomcat();
        Service service = tomcat.getService();
        Connector[] connectors = service == null ? null : service.findConnectors();
        if (connectors == null || connectors.length == 0) {
            throw new IllegalStateException(
                    "LoopbackBindingGuard could not verify the actual bind address: "
                            + "no Tomcat connector was found. Refusing to assume a safe "
                            + "loopback bind.");
        }
        List<String> addresses = new ArrayList<>(connectors.length);
        for (Connector connector : connectors) {
            addresses.add(verifiedBindAddress(connector));
        }
        return addresses;
    }

    /** 单个连接器的真实监听地址;空/空白(通配绑定)与读取失败都算拒绝。 */
    private String verifiedBindAddress(Connector connector) {
        Object raw;
        try {
            raw = connector.getProperty("address");
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "LoopbackBindingGuard could not read the actual bind address of a "
                            + "Tomcat connector (" + connector.getClass().getSimpleName()
                            + "): " + ex.getClass().getSimpleName()
                            + ". Refusing to assume a safe loopback bind.", ex);
        }
        String address = raw instanceof InetAddress inet ? inet.getHostAddress()
                : raw == null ? null : raw.toString();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "A Tomcat connector reports no bind address (empty/null means the "
                            + "wildcard interface) while the product API has no authentication "
                            + "boundary. Set server.address=127.0.0.1 or, for a deliberate "
                            + "network deployment, set SPEC_AGENT_ALLOW_NON_LOOPBACK=true and "
                            + "provide external authentication and isolation.");
        }
        return address.strip();
    }

    static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address.strip()).isLoopbackAddress();
        } catch (Exception ex) {
            // 无法解析的绑定地址按非回环处理(保守方向)。
            return address.strip().toLowerCase(Locale.ROOT).equals("localhost");
        }
    }
}
