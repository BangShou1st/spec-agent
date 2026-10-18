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
 * 没有任何 Agent 逻辑——只做:解析本 Run 的目标 -> 查注册表 -> 委派。
 *
 * <p><b>Run 级模型冻结(Check-then-Act 窗口已关闭)</b>:{@code Project Agent}
 * 回答周期由同一次 Run 内的 STATE_UPDATE 与 DECISION 两次推理组成。路由网关在同一个
 * {@code runId} 的<b>第一次</b>推理时冻结(提供商, 模型)对,之后该 run 的每次推理都必须与
 * 冻结对一致;运行中一旦有人在设置页切换提供商或模型,即失败关闭
 * ({@code MODEL_BINDING_MISMATCH}),<b>绝不自动换模型、绝不自动重试、绝不重定向</b>。
 *
 * <p><b>不用第二次读取消除漂移</b>:目标只在这里解析一次。路由由<b>冻结目标</b>的
 * 提供商决定(不再另行读取当前激活提供商),并且把<b>冻结模型</b>显式下传给具体网关,
 * 由它按该模型构造实际 HTTP 请求——具体网关绝不在此次请求内重新读取模型设置。
 * 因此在"冻结检查"与"网关构造请求"之间发生的设置变更既不会被静默采用,也不会
 * 让本 Run 被改送到新模型;已冻结的运行在目标变得不可解析时同样失败关闭。
 *
 * <p>冻结表按进程内 LRU(<b>访问序</b>)有界保存,是"同一进程内同一个 Run 不得混用模型"的
 * <b>最薄</b>保障:刚刚被推理访问过的运行留在表尾,被淘汰的是最久未使用的运行。本轮评估的
 * 全部场景都在单一执行器进程内完成,因此足够。它<b>不能</b>跨进程重启或多实例:那时冻结对会
 * 丢失,需要把绑定持久化到 run 记录(<b>本轮不做</b>,在最终报告中如实记录为已知限制)。
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

    /**
     * 访问序 LRU:最近被推理访问过的运行留在表尾,因此容量触顶时被淘汰的是
     * 最久未被访问的运行,而不是正在执行的运行。所有复合读写都在同一监视器内完成,
     * 保证"读冻结 + 首次写入"是原子的。
     */
    private final Map<UUID, ActiveModelTargetPort.Target> frozenRunTargets =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
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
        ActiveModelTargetPort.Target binding = resolveBoundTarget(request.runId());
        if (binding == null) {
            // 目标不可解析且本 Run 尚无冻结:交由具体网关按自身类型化错误失败关闭。
            return legacyComplete(request);
        }
        String model = binding.model();
        return switch (ModelProvider.fromCode(binding.provider())) {
            case OPENCODE_ZEN -> openCode.complete(request, model);
            case OPENROUTER -> openRouter.complete(request, model);
            case KILO -> kilo.complete(request, model);
            case CUSTOM -> custom.complete(request, model);
        };
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        ActiveModelTargetPort.Target binding = resolveBoundTarget(request.runId());
        if (binding == null) {
            return legacyCompleteStreaming(request, listener);
        }
        String model = binding.model();
        return switch (ModelProvider.fromCode(binding.provider())) {
            case OPENCODE_ZEN -> openCode.completeStreaming(request, listener, model);
            case OPENROUTER -> openRouter.completeStreaming(request, listener, model);
            case KILO -> kilo.completeStreaming(request, listener, model);
            case CUSTOM -> custom.completeStreaming(request, listener, model);
        };
    }

    /**
     * 目标不可解析的历史路径:仍按当前激活提供商路由,由具体网关按其自身类型化错误
     * 失败关闭。该路径只在"本 Run 尚无冻结"时可达;已冻结的 Run 绝不走到这里。
     */
    private ModelInferenceGateway legacyDelegate() {
        return switch (providerSettings.activeProvider()) {
            case OPENCODE_ZEN -> openCode;
            case OPENROUTER -> openRouter;
            case KILO -> kilo;
            case CUSTOM -> custom;
        };
    }

    private ModelInferenceResponse legacyComplete(ModelInferenceRequest request) {
        return legacyDelegate().complete(request);
    }

    private ModelInferenceResponse legacyCompleteStreaming(ModelInferenceRequest request,
                                                           FragmentListener listener) {
        return legacyDelegate().completeStreaming(request, listener);
    }

    /**
     * 解析本 Run 的(提供商, 模型)绑定:首次调用冻结,之后比对;漂移即失败关闭。
     *
     * <ul>
     *   <li>{@code runId == null} → 无 Run 身份,不冻结也不拦截,按当前目标路由(可能为 null);</li>
     *   <li>已冻结且当前目标与冻结不一致,或当前目标已不可解析 → {@code MODEL_BINDING_MISMATCH}
     *       (绝不静默改送新模型,也不因目标解析失败而跳过冻结);</li>
     *   <li>尚未冻结且目标可解析 → 冻结并返回该目标;</li>
     *   <li>尚未冻结且目标不可解析 → 返回 null,由具体网关失败关闭。</li>
     * </ul>
     */
    private ActiveModelTargetPort.Target resolveBoundTarget(UUID runId) {
        ActiveModelTargetPort.Target current = currentTargetOrNull();
        if (runId == null) {
            return current;
        }
        synchronized (frozenRunTargets) {
            ActiveModelTargetPort.Target frozen = frozenRunTargets.get(runId);
            if (frozen == null) {
                if (current == null) {
                    return null;
                }
                frozenRunTargets.put(runId, current);
                return current;
            }
            if (current == null || !frozen.equals(current)) {
                throw ModelProviderException.providerRequestError("routing",
                        "MODEL_BINDING_MISMATCH: run " + runId + " is frozen to " + frozen
                                + " but the active provider/model is now " + current
                                + "; refusing to retarget the run", null);
            }
            return frozen;
        }
    }

    /** 当前激活提供商与其选中模型的只读投影;不可解析时返回 null。 */
    private ActiveModelTargetPort.Target currentTargetOrNull() {
        try {
            return modelTargets.activeTarget().orElse(null);
        } catch (RuntimeException unresolved) {
            return null;
        }
    }
}
