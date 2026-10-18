package com.specagent.model.inference;

import com.specagent.model.contract.ModelInferenceMessage;

import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.model.provider.KiloInferenceGateway;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.OpenRouterInferenceGateway;
import com.specagent.model.provider.RoutingModelInferenceGateway;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:RoutingModelInferenceGatewayTest.java
 *
 * 测试目标:验证基于 {@link ActiveProviderPort} 的活跃 Provider 路由语义:
 * 每个请求恰好分发给一个委托网关(OpenCode / OpenRouter / Custom),流式调用遵循相同路由;
 * 委托失败时异常直接向上传播,不做跨 Provider 回退、也不重试。
 */
@ExtendWith(MockitoExtension.class)
class RoutingModelInferenceGatewayTest {

    @Mock
    private ActiveProviderPort activeProvider;
    @Mock
    private ActiveModelTargetPort activeModelTarget;
    @Mock
    private OpenCodeModelInferenceGateway openCode;
    @Mock
    private OpenRouterInferenceGateway openRouter;
    @Mock
    private KiloInferenceGateway kilo;
    @Mock
    private CustomInferenceGateway custom;

    private RoutingModelInferenceGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new RoutingModelInferenceGateway(activeProvider, activeModelTarget,
                openCode, openRouter, kilo, custom);
    }

    private static ModelInferenceRequest request() {
        return new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }

    @Test
    void openCodeZenRoutesOnlyToTheOpenCodeDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(openCode).complete(request);
        verifyNoInteractions(openRouter, kilo, custom);
    }

    @Test
    void openRouterRoutesOnlyToTheOpenRouterDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(openRouter).complete(request);
        verifyNoInteractions(openCode, kilo, custom);
    }

    @Test
    void kiloRoutesOnlyToTheKiloDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.KILO);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(kilo).complete(request);
        verifyNoInteractions(openCode, openRouter, custom);
    }

    @Test
    void customRoutesOnlyToTheCustomDelegate() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        ModelInferenceRequest request = request();

        gateway.complete(request);

        verify(custom).complete(request);
        verifyNoInteractions(openCode, openRouter, kilo);
    }

    @Test
    void streamingFollowsTheSameActiveProviderRouting() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        ModelInferenceRequest request = request();
        FragmentListener listener = mock(FragmentListener.class);

        gateway.completeStreaming(request, listener);

        verify(openRouter).completeStreaming(request, listener);
        verifyNoInteractions(openCode, kilo, custom);
    }

    @Test
    void delegateFailurePropagatesWithoutFallbackOrRetry() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        OpenCodeModelException boom = new OpenCodeModelException(
                OpenCodeModelErrorCategory.NOT_CONFIGURED, "provider is not configured");
        doThrow(boom).when(openCode).complete(any());
        ModelInferenceRequest request = request();

        assertThatThrownBy(() -> gateway.complete(request)).isSameAs(boom);

        verify(activeProvider, times(1)).activeProvider();
        verify(openCode, times(1)).complete(request);
        verifyNoInteractions(openRouter, kilo, custom);
    }

    // -- Run 级模型冻结 -------------------------------------------------------------

    /**
     * 确定性证明:同一个 Run 在 STATE_UPDATE 后、DECISION 前被切换提供商/模型时,
     * 第二次推理以 MODEL_BINDING_MISMATCH 失败关闭——绝不重定向到新提供商,也不重试。
     */
    @Test
    void sameRunFailsClosedWhenProviderAndModelDriftBetweenCalls() {
        UUID runId = UUID.randomUUID();
        ModelInferenceRequest stateUpdate = request(runId, "STATE_UPDATE");
        ModelInferenceRequest decision = request(runId, "DECISION");
        java.util.concurrent.atomic.AtomicReference<ModelProvider> provider =
                new java.util.concurrent.atomic.AtomicReference<>(ModelProvider.OPENCODE_ZEN);
        java.util.concurrent.atomic.AtomicReference<ActiveModelTargetPort.Target> target =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new ActiveModelTargetPort.Target("OPENCODE_ZEN", "model-a"));
        when(activeProvider.activeProvider()).thenAnswer(invocation -> provider.get());
        when(activeModelTarget.activeTarget()).thenAnswer(
                invocation -> Optional.of(target.get()));

        gateway.complete(stateUpdate);

        // 运行中切换提供商与模型(DECISION 之前)。
        provider.set(ModelProvider.KILO);
        target.set(new ActiveModelTargetPort.Target("KILO", "model-b"));

        assertThatThrownBy(() -> gateway.complete(decision))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        verify(openCode, times(1)).complete(stateUpdate);
        verifyNoInteractions(kilo, openRouter, custom);
    }

    /** 同一 Run 目标稳定时,两次推理都正常路由,冻结不误伤合法调用。 */
    @Test
    void sameRunWithStableTargetKeepsRoutingBothCalls() {
        UUID runId = UUID.randomUUID();
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.KILO);
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(
                new ActiveModelTargetPort.Target("KILO", "poolside/laguna-s-2.1:free")));
        ModelInferenceRequest stateUpdate = request(runId, "STATE_UPDATE");
        ModelInferenceRequest decision = request(runId, "DECISION");

        gateway.complete(stateUpdate);
        gateway.complete(decision);

        verify(kilo, times(2)).complete(any());
        verifyNoInteractions(openCode, openRouter, custom);
    }

    /** 冻结按 Run 隔离:不同 Run 各自独立,互不影响。 */
    @Test
    void freezeIsPerRunAndDoesNotLeakAcrossRuns() {
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENCODE_ZEN);
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(
                new ActiveModelTargetPort.Target("OPENCODE_ZEN", "model-a")));
        gateway.complete(request(UUID.randomUUID(), "STATE_UPDATE"));

        // 另一个 Run 使用不同的提供商/模型:互不干扰。
        when(activeProvider.activeProvider()).thenReturn(ModelProvider.OPENROUTER);
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(
                new ActiveModelTargetPort.Target("OPENROUTER", "model-c")));
        gateway.complete(request(UUID.randomUUID(), "DECISION"));

        verify(openCode, times(1)).complete(any());
        verify(openRouter, times(1)).complete(any());
    }

    private static ModelInferenceRequest request(UUID runId, String callType) {
        return new ModelInferenceRequest(runId, callType,
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }
}
