package com.specagent.capability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:CapabilityResult.java
 *
 * 用途:一次能力调用后的带类型结果。结果是带有溯源(provenance)的观测——
 * 它们作为外部证据进入后续决策周期,永远不会被自动确认为图谱事实(graph truth)。
 *
 * @param invocationId  调用唯一 ID
 * @param invocationKey 幂等键
 * @param capabilityId  执行的能力标识
 * @param status        调用状态
 * @param content       结果内容
 * @param sourceRefs    来源引用
 * @param provenance    溯源信息
 * @param warnings      警告信息
 */
public record CapabilityResult(
        UUID invocationId,
        String invocationKey,
        String capabilityId,
        Status status,
        Map<String, Object> content,
        List<String> sourceRefs,
        Map<String, Object> provenance,
        List<String> warnings) {

    /**
     * 一次调用的生命周期。{@code RUNNING} 是持久化中"已认领但未完成"的状态;
     * {@code IN_PROGRESS} 是运行时返回给"调用仍在进行中"的后续调用方的带类型应答——
     * 它刻意不是重放,也绝不携带编造的内容。
     */
    public enum Status { SUCCEEDED, FAILED, REPLAYED, RUNNING, IN_PROGRESS }

    public CapabilityResult {
        content = content == null ? Map.of() : Map.copyOf(content);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static CapabilityResult failed(UUID invocationId, String invocationKey,
                                          String capabilityId, String reason) {
        return new CapabilityResult(invocationId, invocationKey, capabilityId,
                Status.FAILED, Map.of("reason", reason == null ? "" : reason),
                List.of(), Map.of(), List.of());
    }
}
