package com.specagent.model.provider;

import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;

import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.ModelProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:RoutingModelInferenceGateway.java
 *
 * 用途:权威的 {@link ModelInferenceGateway} 入口,也是唯一发生"按激活提供商
 * 路由"的地方。这里不做请求构建、不做 SSE 解析、不做降级(fallback)、不重试、
 * 没有任何 Agent 逻辑——只做:查激活提供商 -> 查注册表 -> 委派。
 *
 * <p><b>Run 级模型冻结</b>:{@code Project Agent} 回答周期由同一次 Run 内的
 * STATE_UPDATE 与 DECISION 两次推理组成。路由网关在同一个 {@code runId} 的
 * <b>第一次</b>推理时冻结(提供商, 模型)对,之后该 run 的每次推理都必须与冻结对
 * 一致;运行中一旦有人在设置页切换提供商或模型,即失败关闭
 * ({@code MODEL_BINDING_MISMATCH}),<b>绝不自动换模型、绝不自动重试、绝不重定向</b>。
 * 这与 GA 已冻结绑定的语义一致,但使用独立的最薄实现:只读取既有设置事实,
 * 不新增任何模型选择系统,也不改变 GA 的冻结机制。
 *
 * <p>冻结表按进程内 LRU 有界保存,是"同一进程内同一个 Run 不得混用模型"的
 * <b>最薄</b>保障;本轮评估的全部场景都在单一执行器进程内完成,因此足够。
 * 若将来支持多实例执行同一 Run,应改为把冻结对持久化到 run 记录(本轮不做,如实记录)。
 */
@Component
@Primary
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class RoutingModelInferenceGateway implements ModelInferenceGateway {

    /** 进程内冻结表的容量上限,防止长时间运行下无界增长。 */
    private static final int MAX_FROZEN_RUNS = 4096;

    private final ActiveProviderPort providerSettings;
    private final ActiveModelTargetPort modelTargets;
    private final OpenCodeModelInferenceGateway openCode;
    private final OpenRouterInferenceGateway openRouter;
    private final KiloInferenceGateway kilo;
    private final CustomInferenceGateway custom;

    private final Map<UUID, ActiveModelTargetPort.Target> frozenRunTargets =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<UUID, ActiveModelTargetPort.Target> eldest) {
                    return size() > MAX_FROZEN_RUNS;
                }
            });

    public RoutingModelInferenceGateway(ActiveProviderPort providerSettings,
                                        ActiveModelTargetPort modelTargets,
                                        OpenCodeModelInferenceGateway openCode,
                                        OpenRouterInferenceGateway openRouter,
                                        KiloInferenceGateway kilo,
                                        CustomInferenceGateway custom) {
        this.providerSettings = providerSettings;
        this.modelTargets = modelTargets;
        this.openCode = openCode;
        this.openRouter = openRouter;
        this.kilo = kilo;
        this.custom = custom;
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        return delegate(request).complete(request);
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        return delegate(request).completeStreaming(request, listener);
    }

    private ModelInferenceGateway delegate(ModelInferenceRequest request) {
        freezeOrFailClosed(request.runId());
        ModelProvider active = providerSettings.activeProvider();
        return switch (active) {
            case OPENCODE_ZEN -> openCode;
            case OPENROUTER -> openRouter;
            case KILO -> kilo;
            case CUSTOM -> custom;
        };
    }

    /**
     * Run 级冻结:首次推理记录(提供商, 模型);此后每次比对,漂移即失败关闭。
     * 目标不可解析(提供商未配置)时不冻结也不拦截——由具体网关按其自身类型化
     * 错误失败关闭,绝不因此改变控制流。
     */
    private void freezeOrFailClosed(UUID runId) {
        if (runId == null) {
            return;
        }
        ActiveModelTargetPort.Target target;
        try {
            target = modelTargets.activeTarget().orElse(null);
        } catch (RuntimeException unresolved) {
            return;
        }
        if (target == null) {
            return;
        }
        ActiveModelTargetPort.Target prior = frozenRunTargets.putIfAbsent(runId, target);
        if (prior != null && !prior.equals(target)) {
            throw ModelProviderException.providerRequestError("routing",
                    "MODEL_BINDING_MISMATCH: run " + runId + " is frozen to " + prior
                            + " but the active provider/model is now " + target
                            + "; refusing to retarget the run", null);
        }
    }
}
