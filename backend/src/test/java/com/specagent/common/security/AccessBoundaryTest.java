package com.specagent.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.agent.protocol.AgentProtocol;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AccessBoundaryTest.java
 *
 * 测试目标:交付形态的访问边界。
 * - 回环绑定判定:回环地址放行、非回环默认拒绝启动;
 * - <b>非回环绑定要求实例自带认证层(第二阶段)</b>:OIDC 模式 + 显式知情
 *   开关才放行,LOCAL 模式即使显式知情也拒绝——LOCAL 按设计仅限回环;
 * - 内部密钥:未配置时生成安装实例独有的随机密钥并持久化到文件,
 *   重启(重新初始化)后仍读取同一值;显式配置时原样使用;
 * - 内部推理 broker 对缺失/错误 token 一律 401(带正确 token 才可达)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessBoundaryTest {

    @Autowired MockMvc mockMvc;
    @Autowired AgentBrainProperties brainProperties;

    @Test
    void loopbackAddressesAreAllowed() {
        assertThat(LoopbackBindingGuard.isLoopback("127.0.0.1")).isTrue();
        assertThat(LoopbackBindingGuard.isLoopback("localhost")).isTrue();
        assertThat(LoopbackBindingGuard.isLoopback("::1")).isTrue();
    }

    /**
     * 配置校验发生在 Bean 构造期:非法配置在构造守卫 Bean 时立即失败——
     * Spring 在所有单例实例化之后才启动内嵌服务器,因此拒绝发生在监听之前。
     */
    @Test
    void nonLoopbackBindingIsRejectedByDefault() {
        assertThatThrownBy(() -> new LoopbackBindingGuard("0.0.0.0", false, "OIDC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-loopback");
        assertThatThrownBy(() -> new LoopbackBindingGuard("192.168.1.10", false, "OIDC"))
                .isInstanceOf(IllegalStateException.class);
        // 通配 IPv6 与解析失败的主机同样按非回环拒绝
        assertThatThrownBy(() -> new LoopbackBindingGuard("::", false, "OIDC"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 空监听地址 = 通配监听,绝不是显式回环:必须拒绝启动,不能静默暴露 API。 */
    @Test
    void emptyBindAddressIsRejected() {
        assertThatThrownBy(() -> new LoopbackBindingGuard(null, false, "OIDC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address is empty");
        assertThatThrownBy(() -> new LoopbackBindingGuard("", false, "OIDC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address is empty");
        assertThatThrownBy(() -> new LoopbackBindingGuard("   ", false, "OIDC"))
                .isInstanceOf(IllegalStateException.class);
        // 显式风险开关也不豁免空地址:它只豁免"知情的非回环部署"。
        assertThatThrownBy(() -> new LoopbackBindingGuard("", true, "OIDC"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loopbackBindingPassesAndExplicitOverrideIsHonored() {
        assertThatCode(() -> new LoopbackBindingGuard("127.0.0.1", false, "OIDC"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LoopbackBindingGuard("0.0.0.0", true, "OIDC"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LoopbackBindingGuard("::1", false, "OIDC"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LoopbackBindingGuard("localhost", false, "OIDC"))
                .doesNotThrowAnyException();
    }

    /** LOCAL 模式仅限回环:回环地址照常放行,与模式无关。 */
    @Test
    void localModeStillBindsLoopback() {
        assertThatCode(() -> new LoopbackBindingGuard("127.0.0.1", false, "LOCAL"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LoopbackBindingGuard("::1", false, "LOCAL"))
                .doesNotThrowAnyException();
    }

    /**
     * 第二阶段的核心收紧:非回环绑定必须自带认证层。
     *
     * 回环绑定原本同时承担"网络隔离"与"事实上的访问控制";改绑网络地址会
     * 让后者消失,而一个布尔知情开关不能替代登录。因此 LOCAL 模式无论是否
     * 声明知情都必须拒绝,只有 OIDC 模式才允许对外提供服务。
     */
    @Test
    void nonLoopbackRequiresAnAuthenticatingMode() {
        assertThatThrownBy(() -> new LoopbackBindingGuard("0.0.0.0", true, "LOCAL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback-only")
                .hasMessageContaining("OIDC");
        assertThatThrownBy(() -> new LoopbackBindingGuard("0.0.0.0", true, "local"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LoopbackBindingGuard("0.0.0.0", true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-loopback");
        // 模式大小写不敏感:OIDC 的任意书写都算自带认证层
        assertThatCode(() -> new LoopbackBindingGuard("0.0.0.0", true, "oidc"))
                .doesNotThrowAnyException();
    }

    @Test
    void installSecretIsGeneratedPersistedAndReused(@TempDir Path tempDir) {
        Path secretFile = tempDir.resolve("data/internal-secret.txt");
        var properties = new AgentBrainProperties();
        properties.setInternalSecret("");
        var first = newInstallSecret(properties, secretFile);
        String generated = properties.getInternalSecret();
        assertThat(generated).isNotBlank().hasSize(64);
        assertThat(Files.exists(secretFile)).isTrue();
        // 模拟重启:同一个文件重新加载,密钥保持稳定
        var restarted = new AgentBrainProperties();
        restarted.setInternalSecret("");
        newInstallSecret(restarted, secretFile);
        assertThat(restarted.getInternalSecret()).isEqualTo(generated);
        // 显式配置优先于文件
        var explicit = new AgentBrainProperties();
        explicit.setInternalSecret("configured-value");
        newInstallSecret(explicit, secretFile);
        assertThat(explicit.getInternalSecret()).isEqualTo("configured-value");
        // 当前 Spring 上下文(test profile)拿到的是测试 profile 固定的确定性值
        assertThat(brainProperties.getInternalSecret()).isEqualTo("dev-internal-secret");
    }

    private com.specagent.agent.broker.InstallInternalSecret newInstallSecret(
            AgentBrainProperties properties, Path file) {
        return new com.specagent.agent.broker.InstallInternalSecret(properties, file.toString());
    }

    @Test
    void internalBrokerRejectsMissingAndWrongTokens() throws Exception {
        String body = "{\"protocolVersion\":\"2026-09-01\",\"runId\":\"" +
                java.util.UUID.randomUUID() + "\",\"callType\":\"DECISION\",\"messages\":" +
                "[{\"role\":\"user\",\"content\":\"x\"}],\"maxOutputTokens\":64}";
        mockMvc.perform(post("/internal/v1/model-inference").content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, "wrong-token")
                        .content(body))
                .andExpect(status().isUnauthorized());
        // 正确 token 进入下一层校验(协议错误),不再是认证失败
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, "dev-internal-secret")
                        .content(body))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }
}
