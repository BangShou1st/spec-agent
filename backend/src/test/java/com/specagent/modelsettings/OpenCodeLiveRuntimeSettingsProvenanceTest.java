package com.specagent.modelsettings;

import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.RoutingModelInferenceGateway;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.testing.FakeModelInferenceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件名:OpenCodeLiveRuntimeSettingsProvenanceTest.java
 *
 * 测试目标:证明评估式(external-environment)运行时设置从外部解析:
 * 优先于数据库设置(根本不读取 repository),使用真实传输层与 OpenCode 端点,
 * 凭证来源标记为环境变量名,toString 不泄漏凭证明文;上下文装配上保持
 * RoutingModelInferenceGateway 为权威入口且不存在 Fake 网关。
 */
@SpringBootTest(properties = {
        "spec.agent.model.inference=opencode",
        "spec.agent.model.runtime-settings-source=external-environment",
        "spec.agent.model.external.api-key=external-test-marker-not-a-secret",
        "spec.agent.model.external.selected-model=gpt-5.6-terra",
        "spec.agent.model.opencode.base-url=https://opencode.ai/zen/v1"
})
@ActiveProfiles("test")
class OpenCodeLiveRuntimeSettingsProvenanceTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OpenCodeSettingsService service;

    @Autowired
    private OpenCodeZenTransport transport;

    @Autowired
    private ModelInferenceGateway inferenceGateway;

    @MockBean
    private OpenCodeSettingsRepository repository;

    @Test
    void externalLiveConfigurationWinsOverDatabaseAndUsesRealGateway() {
        when(repository.find()).thenReturn(Optional.of(new OpenCodeSettings(
                "db-placeholder", "-key", "beta-free", Instant.now(), Instant.now())));

        RuntimeOpenCodeSettings resolved = service.requireRuntimeSettings();

        // MODEL PROVIDERS V1:路由网关是权威入口,OpenCode 保持为委托。
        assertThat(inferenceGateway).isInstanceOf(RoutingModelInferenceGateway.class);
        assertThat(context.getBeansOfType(OpenCodeModelInferenceGateway.class)).hasSize(1);
        assertThat(context.getBeansOfType(FakeModelInferenceGateway.class)).isEmpty();
        assertThat(transport.endpoint()).isEqualTo(OpenCodeZenTransport.BASE_URL);
        assertThat(resolved.selectedModel()).isEqualTo("gpt-5.6-terra");
        assertThat(resolved.credentialSource())
                .isEqualTo("external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY");
        assertThat(resolved.toString()).doesNotContain("external-test-marker-not-a-secret");
        verify(repository, never()).find();
    }
}
