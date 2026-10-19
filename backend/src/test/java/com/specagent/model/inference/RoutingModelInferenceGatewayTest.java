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
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.contract.RuntimeOpenRouterSettings;
import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.common.RunModelBindingPort;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:RoutingModelInferenceGatewayTest.java
 *
 * 测试目标:验证权威路由网关的两条语义:
 * <ol>
 *   <li><b>目标不可解析的历史路径</b>:按 {@link ActiveProviderPort} 委派给恰好一个
 *       委托网关,委托失败直接向上传播,不做跨 Provider 回退、也不重试。</li>
 *   <li><b>Run 级模型冻结</b>:目标只解析一次;路由由冻结目标的提供商决定,
 *       并且<b>冻结模型被显式下传给具体网关</b>(具体网关按该模型构造请求),
 *       因此"冻结检查"与"网关构造请求"之间发生的设置切换既不会被静默采用,
 *       也不会把本 Run 改送到新模型——漂移一律 {@code MODEL_BINDING_MISMATCH} 失败关闭。</li>
 * </ol>
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
        return new ModelInferenceRequest(null, "DECISION",
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }

    private static ModelInferenceRequest request(UUID runId, String callType) {
        return new ModelInferenceRequest(runId, callType,
                List.of(new ModelInferenceMessage("user", "{}")), null);
    }

    private static ActiveModelTargetPort.Target frozenTarget(String provider, String model) {
        Object runtime = switch (ModelProvider.fromCode(provider)) {
            case OPENCODE_ZEN -> new RuntimeOpenCodeSettings("key-" + model, model);
            case OPENROUTER -> new RuntimeOpenRouterSettings("key-" + model, model);
            case KILO -> new RuntimeKiloSettings("key-" + model, model);
            case CUSTOM -> new RuntimeCustomSettings("CHAT_COMPLETIONS", "http://127.0.0.1/v1",
                    "key-" + model, model);
        };
        return new ActiveModelTargetPort.Target(provider, model, runtime);
    }

    // -- 目标不可解析时的历史路由(无 Run 身份)-------------------------------

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

    /**
     * 目标可解析且带 Run 身份时,路由由<b>冻结目标</b>决定,并且把冻结模型显式传给
     * 具体网关——路由网关不再另行读取当前激活提供商,也不让网关自行读取模型设置。
     */
    @Test
    void frozenTargetDrivesProviderAndModelWithoutASecondRead() {
        UUID runId = UUID.randomUUID();
        ActiveModelTargetPort.Target frozen = frozenTarget("KILO", "poolside/laguna-s-2.1:free");
        RuntimeKiloSettings runtime = (RuntimeKiloSettings) frozen.runtimeSettings();
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(frozen));
        ModelInferenceRequest stateUpdate = request(runId, "STATE_UPDATE");
        ModelInferenceRequest decision = request(runId, "DECISION");

        gateway.complete(stateUpdate);
        gateway.complete(decision);

        verify(kilo).complete(stateUpdate, runtime);
        verify(kilo).complete(decision, runtime);
        // 冻结目标已经携带提供商,因此不再读取激活提供商端口。
        verifyNoInteractions(activeProvider, openCode, openRouter, custom);
    }

    /**
     * 确定性证明:在同一个 Run 的两次推理之间切换提供商/模型时,第二次推理
     * 以 {@code MODEL_BINDING_MISMATCH} 失败关闭,绝不重定向、不重试,
     * 网关也绝不会被要求用新模型发请求。
     */
    @Test
    void sameRunFailsClosedWhenProviderAndModelDriftBetweenCalls() {
        UUID runId = UUID.randomUUID();
        ModelInferenceRequest stateUpdate = request(runId, "STATE_UPDATE");
        ModelInferenceRequest decision = request(runId, "DECISION");
        AtomicReference<ActiveModelTargetPort.Target> target = new AtomicReference<>(
                frozenTarget("OPENCODE_ZEN", "model-a"));
        RuntimeOpenCodeSettings frozenSettings = (RuntimeOpenCodeSettings) target.get().runtimeSettings();
        when(activeModelTarget.activeTarget()).thenAnswer(invocation -> Optional.of(target.get()));

        gateway.complete(stateUpdate);

        // 运行中把提供商与模型一起切换(DECISION 之前)。
        target.set(frozenTarget("KILO", "model-b"));

        assertThatThrownBy(() -> gateway.complete(decision))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        verify(openCode).complete(stateUpdate, frozenSettings);
        verifyNoMoreInteractions(openCode);
        verifyNoInteractions(kilo, openRouter, custom);
    }

    /**
     * 同一提供商内只切换模型:同样失败关闭,且冻结模型之外的模型绝不会被发出去。
     */
    @Test
    void sameRunFailsClosedWhenOnlyTheModelDrifts() {
        UUID runId = UUID.randomUUID();
        AtomicReference<ActiveModelTargetPort.Target> target = new AtomicReference<>(
                frozenTarget("KILO", "model-a"));
        RuntimeKiloSettings frozenSettings = (RuntimeKiloSettings) target.get().runtimeSettings();
        when(activeModelTarget.activeTarget()).thenAnswer(invocation -> Optional.of(target.get()));

        gateway.complete(request(runId, "STATE_UPDATE"));
        target.set(frozenTarget("KILO", "model-b"));

        assertThatThrownBy(() -> gateway.complete(request(runId, "DECISION")))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        verify(kilo).complete(any(ModelInferenceRequest.class), eq(frozenSettings));
        verifyNoMoreInteractions(kilo);
    }

    /**
     * 已冻结的 Run 若目标变得不可解析(提供商被切换成未配置),同样失败关闭——
     * 绝不因"读不出目标"而跳过冻结校验。
     */
    @Test
    void frozenRunFailsClosedWhenTheTargetBecomesUnresolvable() {
        UUID runId = UUID.randomUUID();
        ActiveModelTargetPort.Target initial = frozenTarget("KILO", "model-a");
        RuntimeKiloSettings frozenSettings = (RuntimeKiloSettings) initial.runtimeSettings();
        when(activeModelTarget.activeTarget())
                .thenReturn(Optional.of(initial))
                .thenThrow(new IllegalStateException("provider is not configured"));

        gateway.complete(request(runId, "STATE_UPDATE"));

        assertThatThrownBy(() -> gateway.complete(request(runId, "DECISION")))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        verify(kilo).complete(any(ModelInferenceRequest.class), eq(frozenSettings));
        verifyNoInteractions(activeProvider, openCode, openRouter, custom);
    }

    /** 冻结按 Run 隔离:不同 Run 各自独立,互不影响。 */
    @Test
    void freezeIsPerRunAndDoesNotLeakAcrossRuns() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ActiveModelTargetPort.Target firstTarget = frozenTarget("OPENCODE_ZEN", "model-a");
        ActiveModelTargetPort.Target secondTarget = frozenTarget("OPENROUTER", "model-c");
        when(activeModelTarget.activeTarget())
                .thenReturn(Optional.of(firstTarget))
                .thenReturn(Optional.of(secondTarget));

        gateway.complete(request(first, "STATE_UPDATE"));
        gateway.complete(request(second, "DECISION"));

        verify(openCode).complete(any(ModelInferenceRequest.class),
                eq((RuntimeOpenCodeSettings) firstTarget.runtimeSettings()));
        verify(openRouter).complete(any(ModelInferenceRequest.class),
                eq((RuntimeOpenRouterSettings) secondTarget.runtimeSettings()));
        verifyNoInteractions(activeProvider, kilo, custom);
    }

    /** 流式调用遵循同一冻结目标,并且同样把冻结模型下传给具体网关。 */
    @Test
    void streamingUsesTheFrozenTargetModel() {
        UUID runId = UUID.randomUUID();
        ActiveModelTargetPort.Target frozen = frozenTarget("OPENROUTER", "model-a");
        RuntimeOpenRouterSettings runtime = (RuntimeOpenRouterSettings) frozen.runtimeSettings();
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(frozen));
        FragmentListener listener = mock(FragmentListener.class);
        ModelInferenceRequest request = request(runId, "DECISION");

        gateway.completeStreaming(request, listener);

        verify(openRouter).completeStreaming(request, listener, runtime);
        verifyNoInteractions(openCode, kilo, custom);
    }

    /** 新 Run 可以使用用户新选择的模型:冻结只约束同一个 Run。 */
    @Test
    void newRunMayUseTheNewlySelectedModel() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ActiveModelTargetPort.Target firstTarget = frozenTarget("KILO", "model-a");
        ActiveModelTargetPort.Target secondTarget = frozenTarget("KILO", "model-b");
        when(activeModelTarget.activeTarget())
                .thenReturn(Optional.of(firstTarget))
                .thenReturn(Optional.of(secondTarget));

        gateway.complete(request(first, "STATE_UPDATE"));
        gateway.complete(request(second, "STATE_UPDATE"));

        verify(kilo).complete(any(ModelInferenceRequest.class),
                eq((RuntimeKiloSettings) firstTarget.runtimeSettings()));
        verify(kilo).complete(any(ModelInferenceRequest.class),
                eq((RuntimeKiloSettings) secondTarget.runtimeSettings()));
    }

    @Test
    void concurrentFirstCallsCannotBindOneRunToTwoDifferentTargets() throws Exception {
        UUID runId = UUID.randomUUID();
        CyclicBarrier bothResolved = new CyclicBarrier(2);
        AtomicInteger resolutions = new AtomicInteger();
        when(activeModelTarget.activeTarget()).thenAnswer(invocation -> {
            int order = resolutions.incrementAndGet();
            bothResolved.await(5, TimeUnit.SECONDS);
            return Optional.of(order == 1
                    ? frozenTarget("OPENCODE_ZEN", "model-a")
                    : frozenTarget("KILO", "model-b"));
        });
        RoutingModelInferenceGateway concurrentGateway = new RoutingModelInferenceGateway(
                activeProvider, activeModelTarget, openCode, openRouter, kilo, custom,
                new InMemoryRunModelBindingPort());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> succeeds(concurrentGateway, request(runId, "STATE_UPDATE")));
            Future<Boolean> second = pool.submit(() -> succeeds(concurrentGateway, request(runId, "DECISION")));

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
            int delegateCalls = mockingDetails(openCode).getInvocations().size()
                    + mockingDetails(openRouter).getInvocations().size()
                    + mockingDetails(kilo).getInvocations().size()
                    + mockingDetails(custom).getInvocations().size();
            assertThat(delegateCalls).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictedPausedRunAndNewGatewayStillCannotSilentlyRebind() {
        UUID pausedRun = UUID.randomUUID();
        AtomicReference<ActiveModelTargetPort.Target> target = new AtomicReference<>(
                frozenTarget("KILO", "model-a"));
        when(activeModelTarget.activeTarget()).thenAnswer(invocation -> Optional.of(target.get()));
        RunModelBindingPort durableBindings = new InMemoryRunModelBindingPort();
        RoutingModelInferenceGateway firstProcess = new RoutingModelInferenceGateway(
                activeProvider, activeModelTarget, openCode, openRouter, kilo, custom, durableBindings);

        firstProcess.complete(request(pausedRun, "STATE_UPDATE"));
        // With the current 4096-entry LRU, insert enough other active Runs to evict the paused one.
        for (int i = 0; i < 4096; i++) {
            firstProcess.complete(request(UUID.randomUUID(), "STATE_UPDATE"));
        }
        target.set(frozenTarget("KILO", "model-b"));

        assertThatThrownBy(() -> firstProcess.complete(request(pausedRun, "DECISION")))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        RoutingModelInferenceGateway restartedProcess = new RoutingModelInferenceGateway(
                activeProvider, activeModelTarget, openCode, openRouter, kilo, custom, durableBindings);
        assertThatThrownBy(() -> restartedProcess.complete(request(pausedRun, "DECISION")))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        // The updated global config is valid for a fresh Run.
        firstProcess.complete(request(UUID.randomUUID(), "STATE_UPDATE"));
    }

    @Test
    void runWithIncompleteRuntimeSnapshotFailsClosedBeforeProviderGateway() {
        when(activeModelTarget.activeTarget()).thenReturn(Optional.of(
                new ActiveModelTargetPort.Target("KILO", "model-a")));

        assertThatThrownBy(() -> gateway.complete(request(UUID.randomUUID(), "STATE_UPDATE")))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("MODEL_BINDING_MISMATCH");

        verifyNoInteractions(openCode, openRouter, kilo, custom);
    }

    private static boolean succeeds(RoutingModelInferenceGateway gateway, ModelInferenceRequest request) {
        try {
            gateway.complete(request);
            return true;
        } catch (ModelProviderException mismatch) {
            return false;
        }
    }
}
