package com.specagent.eval;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.DeterministicEngineFaultPlan;
import com.specagent.agent.decision.LocalDeterministicDecisionEngine;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.testing.FakeModelInferenceGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件名:LiveExecutionGuardTest.java
 *
 * 测试目标:B-fast 与 B-live 边界的回归守护:live 运行必须拒绝 Java 假引擎、
 * 脚本化大脑和假推理网关,只接受 RemotePythonDecisionEngine +
 * OpenCodeModelInferenceGateway;bean 缺失不得当作回退,Provider 配置缺失时
 * 失败关闭且不回退到假实现。
 */
class LiveExecutionGuardTest {

    private final AgentDecisionEngine remoteBrain =
            new RemotePythonDecisionEngine(new AgentBrainProperties());
    private final ModelInferenceGateway realProvider =
            new OpenCodeModelInferenceGateway(null, null);

    @Test
    void javaFakeEngineIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                new LocalDeterministicDecisionEngine(new DeterministicEngineFaultPlan()), realProvider, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentDecisionEngine")
                .hasMessageContaining("RemotePythonDecisionEngine");
    }

    @Test
    void scriptedBrainIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, realProvider, new ScriptedBrain()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScriptedBrain");
    }

    @Test
    void fakeInferenceGatewayIsRejectedAsLive() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, new FakeModelInferenceGateway(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ModelInferenceGateway")
                .hasMessageContaining("fake providers are not live");
    }

    @Test
    void remotePythonAndOpenCodeGatewayAreAccepted() {
        LiveExecutionGuard.Evidence evidence = LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, realProvider, null);

        assertThat(evidence.decisionEngine())
                .isEqualTo(RemotePythonDecisionEngine.class.getName());
        assertThat(evidence.inferenceGateway())
                .isEqualTo(OpenCodeModelInferenceGateway.class.getName());
    }

    /**
     * 回归:生产装配把 RoutingModelInferenceGateway 作为 @Primary 的权威入口
     * (按激活提供商委派)。守卫必须接受它,否则真实 live 链路会被误判为 fake,
     * 让 P3-A 的真实模型评测无法进行。
     */
    @Test
    void productionRoutingGatewayIsAcceptedAsLive() {
        ModelInferenceGateway routing = new com.specagent.model.provider
                .RoutingModelInferenceGateway(
                () -> com.specagent.model.contract.ModelProvider.OPENCODE_ZEN,
                java.util.Optional::<com.specagent.model.contract.ActiveModelTargetPort.Target>empty,
                new OpenCodeModelInferenceGateway(null, null), null, null, null);

        LiveExecutionGuard.Evidence evidence = LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, routing, null);

        assertThat(evidence.inferenceGateway())
                .isEqualTo(com.specagent.model.provider.RoutingModelInferenceGateway.class.getName());
    }

    /** 未知网关仍必须被拒绝:绝不放宽成"不是 fake 就算 live"。 */
    @Test
    void unknownGatewayIsStillRejectedAsLive() {
        ModelInferenceGateway unknown = new ModelInferenceGateway() {
            @Override
            public com.specagent.model.contract.ModelInferenceResponse complete(
                    ModelInferenceRequest request) {
                return null;
            }

            @Override
            public com.specagent.model.contract.ModelInferenceResponse completeStreaming(
                    ModelInferenceRequest request,
                    com.specagent.model.contract.FragmentListener listener) {
                return null;
            }
        };

        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(
                remoteBrain, unknown, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("real provider gateway");
    }

    @Test
    void missingBeansAreRejectedRatherThanTreatedAsFallback() {
        assertThatThrownBy(() -> LiveExecutionGuard.requireRemoteProvider(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected RemotePythonDecisionEngine");
    }

    @Test
    void missingProviderConfigurationFailsClosedWithoutFakeFallback() {
        OpenCodeRuntimeSettingsPort settings = mock(OpenCodeRuntimeSettingsPort.class);
        when(settings.requireRuntimeSettings()).thenThrow(
                new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                        "provider is not configured"));
        OpenCodeModelInferenceGateway gateway =
                new OpenCodeModelInferenceGateway(null, settings);

        assertThatThrownBy(() -> gateway.complete(new ModelInferenceRequest(
                java.util.UUID.randomUUID(), "DECISION",
                java.util.List.of(new ModelInferenceMessage("user", "test")), 128)))
                .isInstanceOf(OpenCodeModelException.class)
                .satisfies(error -> assertThat(((OpenCodeModelException) error).gatewayCategory())
                        .isEqualTo(ModelGatewayErrorCategory.NOT_CONFIGURED));
    }
}
