package com.specagent.common.security;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:LoopbackBindingGuardStartupTest.java
 *
 * 测试目标:回环守卫的真实启动行为(第二轮复核 R2-A 的闭合)。所有用例都
 * 通过真实 Spring/Tomcat 上下文启动并观察实际连接器行为,而不是直接调用
 * 守卫方法:
 * - 回环配置的真实上下文启动成功,守卫读取实际连接器地址并放行;
 * - 非回环配置在真实启动中失败(配置守卫,监听之前);
 * - 配置声明回环但连接器实际绑定通配地址时,真实启动在实际绑定校验处
 *   失败——配置与实际绑定的差异不会静默放行(旧实现反射调用的
 *   {@code Connector.getProperties()} 在本项目的 Tomcat 上不存在,异常
 *   被吞掉后检查形同虚设);
 * - 额外连接器未设置监听地址(通配绑定)时 fail closed;
 * - IPv6 回环放行;显式知情部署开关对实际绑定校验生效。
 */
class LoopbackBindingGuardStartupTest {

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackBindingGuard.class)
    static class GuardedApp {
        @Bean
        TomcatServletWebServerFactory factory() {
            // 最小上下文没有自动配置的地址定制器;工厂通过连接器定制器应用
            // 监听地址(与产品路径 TomcatWebServerFactoryCustomizer 的落点
            // 相同:Connector.setProperty("address", ...))。
            var factory = new TomcatServletWebServerFactory(0);
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("address", "127.0.0.1"));
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackBindingGuard.class)
    static class Ipv6LoopbackApp {
        @Bean
        TomcatServletWebServerFactory factory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("address", "::1"));
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackBindingGuard.class)
    static class DivergentBindingApp {
        @Bean
        TomcatServletWebServerFactory factory() {
            var factory = new TomcatServletWebServerFactory(0);
            // 定制器在工厂设置地址之后执行:模拟配置(127.0.0.1)与实际
            // 绑定(0.0.0.0)的真实分歧。
            TomcatConnectorCustomizer wildcard = connector ->
                    connector.setProperty("address", "0.0.0.0");
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("address", "127.0.0.1"));
            factory.addConnectorCustomizers(wildcard);
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackBindingGuard.class)
    static class DeclaredNonLoopbackApp {
        @Bean
        TomcatServletWebServerFactory factory() {
            var factory = new TomcatServletWebServerFactory(0);
            TomcatConnectorCustomizer wildcard = connector ->
                    connector.setProperty("address", "0.0.0.0");
            factory.addConnectorCustomizers(wildcard);
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LoopbackBindingGuard.class)
    static class AdditionalUnsetConnectorApp {
        @Bean
        TomcatServletWebServerFactory factory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("address", "127.0.0.1"));
            // 额外连接器(如管理端口)没有设置监听地址 → 通配绑定 → 拒绝。
            var unset = new Connector();
            unset.setPort(0);
            factory.addAdditionalTomcatConnectors(unset);
            return factory;
        }
    }

    private SpringApplication app(Class<?> configuration) {
        var app = new SpringApplication(configuration);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return app;
    }

    /** 真实 Spring 启动(回环配置):上下文起来,实际监听回环,守卫放行。 */
    @Test
    void realSpringContextStartsWithLoopbackBinding() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(GuardedApp.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of("spring.main.banner-mode", "off"))
                .run();
        try {
            var webServer = (ServletWebServerApplicationContext) context;
            assertThat(webServer.getWebServer().getPort()).isPositive();
            Object rawAddress = ((TomcatWebServer) webServer.getWebServer())
                    .getTomcat().getService().findConnectors()[0].getProperty("address");
            assertThat(((java.net.InetAddress) rawAddress).getHostAddress())
                    .isEqualTo("127.0.0.1");
        } finally {
            context.close();
        }
    }

    /** IPv6 回环绑定:真实启动成功。 */
    @Test
    void ipv6LoopbackBindingStarts() {
        var context = app(Ipv6LoopbackApp.class).run();
        try {
            assertThat(((ServletWebServerApplicationContext) context)
                    .getWebServer().getPort()).isPositive();
        } finally {
            context.close();
        }
    }

    /** 真实 Spring 启动(通配配置):配置守卫在监听开始之前拒绝整个启动。 */
    @Test
    void realSpringContextRefusesWildcardConfiguration() {
        assertThatThrownBy(() -> app(GuardedApp.class).run(
                        "--server.address=0.0.0.0"))
                .hasStackTraceContaining("non-loopback");
    }

    /**
     * 配置声明回环,但连接器被(定制器等)实际改绑通配地址:真实启动在
     * 实际绑定校验处失败。这是对"配置字符串不等于实际绑定"的直接证明。
     */
    @Test
    void realSpringContextRefusesActualWildcardBindDespiteLoopbackConfig() {
        assertThatThrownBy(() -> app(DivergentBindingApp.class).run())
                .hasStackTraceContaining("actually bound to a non-loopback address")
                .hasStackTraceContaining("0.0.0.0");
    }

    /** 任一连接器未设置监听地址(null = 通配)即 fail closed,绝不当作安全回环。 */
    @Test
    void connectorWithoutBindAddressFailsRealStartup() {
        assertThatThrownBy(() -> app(AdditionalUnsetConnectorApp.class).run())
                .hasStackTraceContaining("no bind address");
    }

    /** 显式知情部署开关对实际绑定校验同样生效(知情的通配部署被放行)。 */
    @Test
    void explicitRiskSwitchCoversActualBindVerification() {
        var context = app(DeclaredNonLoopbackApp.class).run(
                "--spec.agent.security.allow-non-loopback=true");
        try {
            assertThat(((ServletWebServerApplicationContext) context)
                    .getWebServer().getPort()).isPositive();
        } finally {
            context.close();
        }
    }
}
