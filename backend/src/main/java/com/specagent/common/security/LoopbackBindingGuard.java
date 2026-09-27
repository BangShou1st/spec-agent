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
 * 用途:本机单用户交付的访问边界守卫。两层防线:
 *
 * 1. 配置守卫(Bean 构造期,即上下文刷新的单例实例化阶段):Spring Boot
 *    在所有单例 Bean 创建完成后才启动内嵌 Web 服务器,因此构造期抛出
 *    异常必然发生在监听之前——这是"监听前拒绝非法配置"的可靠生命周期
 *    挂点(早期草稿曾用 ApplicationRunner,但它在 Web 服务器启动之后才
 *    执行,防线来得太晚)。{@code server.address} 为空/空白不是"显式
 *    回环"——嵌入式服务器会把空地址解释为通配监听,因此拒绝启动;显式
 *    非回环地址同样拒绝,除非操作者通过
 *    {@code SPEC_AGENT_ALLOW_NON_LOOPBACK=true} 明确接受风险(并承诺
 *    自行提供认证入口/网络隔离)。
 * 2. 实际绑定校验({@link WebServerInitializedEvent}):服务器启动后读取
 *    <strong>每一个</strong> Tomcat 连接器真实生效的监听地址,再次确认
 *    回环——配置字符串与实际绑定之间的差异(环境变量、命令行覆盖、
 *    定制器等)不会静默放行。校验用项目 Tomcat 真实支持的
 *    {@link Connector#getProperty(String)} API(旧草稿反射调用的
 *    {@code getProperties()} 在本项目的 Tomcat 上不存在,异常被吞掉后
 *    检查形同虚设);地址读取不到或为空一律视为通配绑定并 fail closed,
 *    绝不把"读取失败"或"地址未知"当作安全回环。
 */
@Component
public class LoopbackBindingGuard {

    private final String serverAddress;
    private final boolean allowNonLoopback;

    public LoopbackBindingGuard(
            @Value("${server.address:}") String serverAddress,
            @Value("${spec.agent.security.allow-non-loopback:${SPEC_AGENT_ALLOW_NON_LOOPBACK:false}}")
            boolean allowNonLoopback) {
        this.serverAddress = serverAddress;
        this.allowNonLoopback = allowNonLoopback;
        validateConfiguration(serverAddress, allowNonLoopback);
    }

    /** 配置层守卫;构造期调用,拒绝空地址与未声明的非回环绑定。 */
    void validateConfiguration(String address, boolean allowNonLoopback) {
        // 空/空白地址不是显式回环:嵌入式服务器会退化为通配监听,
        // 把无认证的产品 API 暴露到网络。必须在监听开始前拒绝。
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "server.address is empty: the embedded server would listen on all "
                            + "interfaces while the product API has no authentication boundary. "
                            + "Set server.address=127.0.0.1 (the delivery default) or, for a "
                            + "deliberate network deployment, provide external authentication "
                            + "and set SPEC_AGENT_ALLOW_NON_LOOPBACK=true.");
        }
        if (isLoopback(address)) {
            return;
        }
        if (allowNonLoopback) {
            // 明确知情的网络部署:产品 API 本身没有认证层,必须由部署者
            // 提供反向代理/防火墙/认证入口,这里只留下醒目的运行记录。
            return;
        }
        throw new IllegalStateException(
                "server.address is set to a non-loopback address (" + address
                        + ") but the product API has no authentication boundary. "
                        + "For the supported single-user delivery keep server.address=127.0.0.1 "
                        + "(default). For a deliberate network deployment set "
                        + "SPEC_AGENT_ALLOW_NON_LOOPBACK=true and provide external "
                        + "authentication and isolation.");
    }

    /**
     * 服务器初始化后校验实际生效的监听地址。逐个连接器读取真实绑定地址:
     * 非回环、或读取不到/为空(通配绑定)时启动失败——除非操作者已显式
     * 声明知情的网络部署。事件在服务器开始监听后发布,抛出异常会使应用
     * 启动失败退出,不会继续对外提供服务。
     */
    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        List<String> addresses = actualBindAddresses(event);
        for (String actual : addresses) {
            if (isLoopback(actual)) {
                continue;
            }
            if (allowNonLoopback) {
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
