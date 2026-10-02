package com.specagent.capability;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:CapabilityRuntime.java
 *
 * 用途:在宿主运行时契约之下执行能力调用,是能力执行的统一入口与守门人。
 *
 * 幂等/重试元数据由运行时持有:对于同一个 invocation key,数据库通过唯一索引上的
 * 原子认领把执行权授予恰好一个调用方;落败者和后续重试都会读回已记录的结果,
 * 而不是再次执行适配器。SUCCEEDED 的调用会重放已记录的结果,FAILED 的调用
 * 重放已记录的失败,不会发起新的外部尝试。
 *
 * 诚实的崩溃窗口语义:如果进程在适配器完成外部工作之后、{@code complete}
 * 持久化结果之前崩溃,调用会停留在 RUNNING 状态,后续调用方会收到带类型的
 * {@link CapabilityResult.Status#IN_PROGRESS IN_PROGRESS} 状态——既不是编造的重放,
 * 也不是静默的重新执行。仅靠本地事务无法保证外部副作用恰好执行一次;因此,
 * 涉及外部系统的适配器必须把 {@link CapabilityInvocation} 携带的运行时身份
 * 作为下游系统的幂等键。当前内置能力均为只读/内部能力,不受影响。
 */
@Service
public class CapabilityRuntime {

    private final CapabilityRegistry registry;
    private final CapabilityInvocationRepository invocationRepository;

    public CapabilityRuntime(CapabilityRegistry registry,
                             CapabilityInvocationRepository invocationRepository) {
        this.registry = registry;
        this.invocationRepository = invocationRepository;
    }

    /**
     * 执行(或重放)一次项目范围内的调用。调用方必须先通过针对描述符副作用分类的
     * 策略检查——运行时负责幂等与类型化,策略负责授权。
     *
     * 严格限定项目范围:{@code projectId} 必须非空,否则失败关闭。
     * 应用范围内的调用方必须使用 {@link #invokeApplicationScoped}。
     */
    @Transactional
    public CapabilityResult invoke(String invocationKey,
                                   String capabilityId,
                                   UUID projectId,
                                   UUID runId,
                                   Map<String, Object> arguments) {
        if (projectId == null) {
            throw new IllegalArgumentException(
                    "Project-scoped invoke requires a non-null projectId; use invokeApplicationScoped for application scope");
        }
        return execute(invocationKey, capabilityId, projectId, runId, arguments);
    }

    /**
     * Global Assistant 宿主工具唯一的应用范围公开入口。持久化时 {@code project_id = NULL},
     * 同时复用与项目范围完全相同的 注册表 → 适配器 → claim/complete/重放 通路,
     * 不使用假项目 ID。
     */
    @Transactional
    public CapabilityResult invokeApplicationScoped(String invocationKey,
                                                     String capabilityId,
                                                     UUID runId,
                                                     Map<String, Object> arguments) {
        return execute(invocationKey, capabilityId, null, runId, arguments);
    }

    public record PreparedApplicationCall(String capabilityId, Map<String,Object> arguments,
            java.util.function.Function<CapabilityInvocation,CapabilityResult> commit) {
        public PreparedApplicationCall { arguments = Map.copyOf(arguments); }
    }

    /** Called only after the caller has durably reserved and authorized this invocation. */
    public PreparedApplicationCall prepareApplicationScoped(String capabilityId, Map<String,Object> arguments,
            java.util.function.BooleanSupplier active) {
        return prepareApplicationScoped(capabilityId,arguments,active,null);
    }
    public PreparedApplicationCall prepareApplicationScoped(String capabilityId,Map<String,Object> arguments,
            java.util.function.BooleanSupplier active,UUID runId) {
        CapabilityAdapter adapter = registry.findAdapter(capabilityId).orElse(null);
        if (!(adapter instanceof PreparedCapabilityAdapter prepared)) return null;
        if (!active.getAsBoolean()) throw new IllegalStateException("GA_EXECUTION_FENCE");
        var commit = prepared.prepare(arguments, active,runId);
        if (!active.getAsBoolean()) throw new IllegalStateException("GA_EXECUTION_FENCE");
        return new PreparedApplicationCall(capabilityId, arguments, commit);
    }

    @Transactional
    public CapabilityResult invokePreparedApplicationScoped(String invocationKey, String capabilityId, UUID runId,
            Map<String,Object> arguments, PreparedApplicationCall prepared) {
        if (!prepared.capabilityId().equals(capabilityId) || !prepared.arguments().equals(arguments))
            throw new IllegalArgumentException("Prepared capability identity mismatch");
        return execute(invocationKey, capabilityId, null, runId, arguments, prepared);
    }

    private CapabilityResult execute(String invocationKey,
                                     String capabilityId,
                                     UUID projectId,
                                     UUID runId,
                                     Map<String, Object> arguments) {
        return execute(invocationKey, capabilityId, projectId, runId, arguments, null);
    }

    private CapabilityResult execute(String invocationKey, String capabilityId, UUID projectId, UUID runId,
            Map<String,Object> arguments, PreparedApplicationCall prepared) {
        CapabilityInvocation invocation = new CapabilityInvocation(
                Ids.random(), invocationKey, capabilityId, projectId, runId, arguments);

        if (!invocationRepository.claim(invocation)) {
            // 认领竞态落败,或重试了一个已知的 key:已记录的行——而不是再执行一次
            // 适配器——才是唯一事实来源。
            CapabilityInvocationRecord existing = invocationRepository
                    .findByInvocationKey(invocationKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Invocation record vanished after losing its claim race: "
                                    + invocationKey));
            return replayResult(existing);
        }

        CapabilityAdapter adapter = registry.findAdapter(capabilityId).orElse(null);
        if (adapter == null) {
            CapabilityResult failed = CapabilityResult.failed(
                    invocation.invocationId(), invocationKey, capabilityId,
                    "Unknown capability id: " + capabilityId);
            invocationRepository.complete(invocation.invocationId(), failed);
            return failed;
        }

        CapabilityResult result;
        try {
            result = prepared == null ? adapter.invoke(invocation) : prepared.commit().apply(invocation);
        } catch (RuntimeException ex) {
            result = CapabilityResult.failed(
                    invocation.invocationId(), invocationKey, capabilityId,
                    "Capability execution failed: " + ex.getClass().getSimpleName());
        }
        invocationRepository.complete(invocation.invocationId(), result);
        return result;
    }

    private CapabilityResult replayResult(CapabilityInvocationRecord record) {
        // "已认领但未完成"的调用(存在并发属主,或持有方进程已崩溃)应如实呈现为
        // 进行中状态:既不能伪装成成功的重放,也不能在这里重新执行。
        if (record.status() == CapabilityResult.Status.RUNNING) {
            return new CapabilityResult(
                    record.id(),
                    record.invocationKey(),
                    record.capabilityId(),
                    CapabilityResult.Status.IN_PROGRESS,
                    Map.of("reason",
                            "invocation is still running (or was interrupted before completion); "
                                    + "no result has been recorded yet"),
                    List.of(), Map.of(), List.of());
        }
        Map<String, Object> stored = record.result() == null ? Map.of() : record.result();
        return new CapabilityResult(
                record.id(),
                record.invocationKey(),
                record.capabilityId(),
                record.status() == CapabilityResult.Status.FAILED
                        ? CapabilityResult.Status.FAILED
                        : CapabilityResult.Status.REPLAYED,
                asMap(stored.get("content")),
                asStrings(stored, "sourceRefs"),
                asMap(stored.get("provenance")),
                asStrings(stored, "warnings"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private java.util.List<String> asStrings(Map<String, Object> stored, String key) {
        Object value = stored.get(key);
        return value instanceof java.util.List<?> list
                ? list.stream().map(String::valueOf).toList()
                : java.util.List.of();
    }
}
