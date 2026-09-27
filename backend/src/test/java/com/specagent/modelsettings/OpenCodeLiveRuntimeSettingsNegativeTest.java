package com.specagent.modelsettings;

import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.RoutingModelInferenceGateway;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.testing.FakeModelInferenceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 文件名:OpenCodeLiveRuntimeSettingsNegativeTest.java
 *
 * 测试目标:验证外部评估环境缺少凭证/模型配置时 fail-closed:
 * 在任何数据库查询或兜底发生之前就抛出 OpenCodeModelException(报文包含缺失的环境变量名,
 * 并明确不使用测试数据库设置);上下文装配上保持 RoutingModelInferenceGateway 为权威入口、
 * OpenCode 为唯一委托,且不存在 Fake 网关。
 */
@SpringBootTest(properties = {
        "spec.agent.model.inference=opencode",
        "spec.agent.model.runtime-settings-source=external-environment",
        "spec.agent.model.external.api-key=",
        "spec.agent.model.external.selected-model=",
        "spec.agent.model.opencode.base-url=https://opencode.ai/zen/v1"
})
@ActiveProfiles("test")
class OpenCodeLiveRuntimeSettingsNegativeTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OpenCodeSettingsService service;

    @Autowired
    private ModelInferenceGateway inferenceGateway;

    @MockBean
    private OpenCodeSettingsRepository repository;

    @Test
    void missingLiveConfigurationFailsClosedWithoutTestDatabaseOrFakeFallback() {
        // MODEL PROVIDERS V1:路由网关是权威入口,OpenCode 保持为委托。
        assertThat(inferenceGateway).isInstanceOf(RoutingModelInferenceGateway.class);
        assertThat(context.getBeansOfType(OpenCodeModelInferenceGateway.class)).hasSize(1);
        assertThat(context.getBeansOfType(FakeModelInferenceGateway.class)).isEmpty();

        assertThatThrownBy(service::requireRuntimeSettings)
                .isInstanceOf(OpenCodeModelException.class)
                .hasMessageContaining("Live OpenCode provider configuration is missing")
                .hasMessageContaining("SPEC_AGENT_EVAL_OPENCODE_KEY")
                .hasMessageContaining("SPEC_AGENT_EVAL_OPENCODE_MODEL")
                .hasMessageContaining("test database settings are not used")
                .doesNotHaveToString("beta-free");
        verifyNoInteractions(repository);
    }
}
