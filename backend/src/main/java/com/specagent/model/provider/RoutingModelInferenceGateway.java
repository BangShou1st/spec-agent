package com.specagent.model.provider;

import com.specagent.model.contract.ActiveModelTargetPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.common.RunModelBindingPort;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Project Agent 的权威推理入口。它解析一次不可变运行配置快照,用快照指纹冻结 Run,
 * 再把同一快照交给具体网关生成请求。凭据、Custom endpoint 和 API 格式不会在冻结后
 * 通过网关的第二次设置读取发生漂移。
 *
 * <p>4096 项访问序 LRU 仅作为快速比较缓存。首次绑定原子写入现有 {@code agent_runs} 行,
 * 所以缓存淘汰、服务重启和多实例都不能清除约束。数据库只保存配置 SHA-256 指纹,
 * 不保存明文凭据。Run 不存在、已终态或配置发生变化时失败关闭。
 */
@Component
@Primary
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "opencode", matchIfMissing = true)
public class RoutingModelInferenceGateway implements ModelInferenceGateway {

    private static final int MAX_FROZEN_RUNS = 4096;

    private final ActiveProviderPort providerSettings;
    private final ActiveModelTargetPort modelTargets;
    private final OpenCodeModelInferenceGateway openCode;
    private final KiloInferenceGateway kilo;
    private final CustomInferenceGateway custom;
    private final RunModelBindingPort runBindings;

    /** LRU 是性能缓存;持久化 Run 绑定才是约束来源。 */
    private final Map<UUID, String> frozenRunTargets =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > MAX_FROZEN_RUNS;
                }
            });

    /** Direct construction is used by unit tests; Spring uses the durable seven argument constructor. */
    public RoutingModelInferenceGateway(ActiveProviderPort providerSettings,
                                        ActiveModelTargetPort modelTargets,
                                        OpenCodeModelInferenceGateway openCode,
                                        KiloInferenceGateway kilo,
                                        CustomInferenceGateway custom) {
        this(providerSettings, modelTargets, openCode, kilo, custom,
                new InMemoryRunModelBindingPort());
    }

    @Autowired
    public RoutingModelInferenceGateway(ActiveProviderPort providerSettings,
                                        ActiveModelTargetPort modelTargets,
                                        OpenCodeModelInferenceGateway openCode,
                                        KiloInferenceGateway kilo,
                                        CustomInferenceGateway custom,
                                        RunModelBindingPort runBindings) {
        this.providerSettings = providerSettings;
        this.modelTargets = modelTargets;
        this.openCode = openCode;
        this.kilo = kilo;
        this.custom = custom;
        this.runBindings = runBindings;
    }

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        ActiveModelTargetPort.Target target = resolveBoundTarget(request.runId());
        if (target == null) {
            if (request.runId() != null) {
                throw bindingMismatch(request.runId());
            }
            return legacyDelegate().complete(request);
        }
        return switch (ModelProvider.fromCode(target.provider())) {
            case OPENCODE_ZEN -> completeOpenCode(request, target);
            case KILO -> completeKilo(request, target);
            case CUSTOM -> completeCustom(request, target);
        };
    }

    @Override
    public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
        ActiveModelTargetPort.Target target = resolveBoundTarget(request.runId());
        if (target == null) {
            if (request.runId() != null) {
                throw bindingMismatch(request.runId());
            }
            return legacyDelegate().completeStreaming(request, listener);
        }
        return switch (ModelProvider.fromCode(target.provider())) {
            case OPENCODE_ZEN -> completeOpenCodeStreaming(request, listener, target);
            case KILO -> completeKiloStreaming(request, listener, target);
            case CUSTOM -> completeCustomStreaming(request, listener, target);
        };
    }

    private ModelInferenceResponse completeOpenCode(ModelInferenceRequest request,
                                                     ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeOpenCodeSettings snapshot
                ? openCode.complete(request, snapshot) : openCode.complete(request, target.model());
    }



    private ModelInferenceResponse completeKilo(ModelInferenceRequest request,
                                                 ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeKiloSettings snapshot
                ? kilo.complete(request, snapshot) : kilo.complete(request, target.model());
    }

    private ModelInferenceResponse completeCustom(ModelInferenceRequest request,
                                                   ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeCustomSettings snapshot
                ? custom.complete(request, snapshot) : custom.complete(request, target.model());
    }

    private ModelInferenceResponse completeOpenCodeStreaming(ModelInferenceRequest request,
            FragmentListener listener, ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeOpenCodeSettings snapshot
                ? openCode.completeStreaming(request, listener, snapshot)
                : openCode.completeStreaming(request, listener, target.model());
    }



    private ModelInferenceResponse completeKiloStreaming(ModelInferenceRequest request,
            FragmentListener listener, ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeKiloSettings snapshot
                ? kilo.completeStreaming(request, listener, snapshot)
                : kilo.completeStreaming(request, listener, target.model());
    }

    private ModelInferenceResponse completeCustomStreaming(ModelInferenceRequest request,
            FragmentListener listener, ActiveModelTargetPort.Target target) {
        return target.runtimeSettings() instanceof RuntimeCustomSettings snapshot
                ? custom.completeStreaming(request, listener, snapshot)
                : custom.completeStreaming(request, listener, target.model());
    }

    /** Current provider fallback is used only when no target can be resolved for an unbound call. */
    private ModelInferenceGateway legacyDelegate() {
        return switch (providerSettings.activeProvider()) {
            case OPENCODE_ZEN -> openCode;
            case KILO -> kilo;
            case CUSTOM -> custom;
        };
    }

    /**
     * Resolve the request snapshot once, then atomically bind/verify its non-secret digest.
     * The LRU lock closes the same-process check/insert race; the database update closes races
     * between instances and survives eviction/restart.
     */
    private ActiveModelTargetPort.Target resolveBoundTarget(UUID runId) {
        if (runId == null) {
            return currentTargetOrNull();
        }
        ActiveModelTargetPort.Target current;
        var frozenReference = runBindings.connectionReference(runId);
        if (frozenReference.isPresent()) {
            try { current = modelTargets.targetForConnectionReference(frozenReference.get()).orElse(null); }
            catch (RuntimeException unavailable) { throw bindingMismatch(runId); }
        } else current = currentTargetOrNull();
        synchronized (frozenRunTargets) {
            String fingerprint = current == null ? null : current.bindingFingerprint();
            String reference = current != null && current.runtimeSettings() instanceof RuntimeCustomSettings custom
                    && custom.providerId() != null ? custom.bindingRevision() : null;
            if (current != null && ("OPENCODE_ZEN".equals(current.provider()) || "KILO".equals(current.provider()))) {
                reference = "builtin:" + current.provider();
            }
            String cached = frozenRunTargets.get(runId);
            if (current == null || !hasProviderSnapshot(current)
                    || (cached != null && !cached.equals(fingerprint))
                    || !(reference == null ? runBindings.bindOrVerify(runId, fingerprint)
                        : runBindings.bindOrVerify(runId, fingerprint, reference))) {
                throw bindingMismatch(runId);
            }
            if (cached == null) {
                frozenRunTargets.put(runId, fingerprint);
            }
            return current;
        }
    }

    /** A Run must never fall back to a provider overload that rereads mutable settings. */
    private static boolean hasProviderSnapshot(ActiveModelTargetPort.Target target) {
        try {
            return switch (ModelProvider.fromCode(target.provider())) {
                case OPENCODE_ZEN -> target.runtimeSettings() instanceof RuntimeOpenCodeSettings;
                case KILO -> target.runtimeSettings() instanceof RuntimeKiloSettings;
                case CUSTOM -> target.runtimeSettings() instanceof RuntimeCustomSettings;
            };
        } catch (RuntimeException invalidProvider) {
            return false;
        }
    }

    private static ModelProviderException bindingMismatch(UUID runId) {
        return ModelProviderException.providerRequestError("routing",
                "MODEL_BINDING_MISMATCH: run " + runId
                        + " has no active durable binding or its model configuration changed; refusing to retarget",
                null);
    }

    private ActiveModelTargetPort.Target currentTargetOrNull() {
        try {
            return modelTargets.activeTarget().orElse(null);
        } catch (RuntimeException unresolved) {
            return null;
        }
    }
}
