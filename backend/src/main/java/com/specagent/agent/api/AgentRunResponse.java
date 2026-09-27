package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runevent.RunProgressView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:AgentRunResponse.java
 *
 * 用途:agent run 的面向运维者的安全读取 DTO。
 *
 * 只暴露 run 元数据和安全的 trace 步骤列表。存储的 trace 是换行拼接的
 * 诊断性生命周期步骤序列(例如 {@code ["created", "context_built",
 * "model_called:DRAFT_NODE", "completed"]});它绝不携带 API 凭据、原始
 * 提示词、原始模型/provider 载荷或堆栈跟踪,本 DTO 同样不携带。控制器会
 * 先把持久化的 trace 解码成步骤列表,再调用 {@link #from}。
 */
public record AgentRunResponse(
        UUID id,
        UUID projectId,
        UUID routeId,
        String triggerType,
        UUID inputNodeId,
        UUID contextSnapshotId,
        UUID producedNodeId,
        UUID producedAnswerId,
        UUID producedPatchId,
        UUID producedSpecSnapshotId,
        String status,
        List<String> traceSteps,
        RunProgressView progress,
        Instant createdAt,
        Instant completedAt) {

    public static AgentRunResponse from(AgentRun run, List<String> traceSteps) {
        return from(run, traceSteps, null);
    }

    public static AgentRunResponse from(AgentRun run, List<String> traceSteps,
                                        RunProgressView progress) {
        return new AgentRunResponse(
                run.id(),
                run.projectId(),
                run.routeId(),
                run.triggerType().code(),
                run.inputNodeId(),
                run.contextSnapshotId(),
                run.producedNodeId(),
                run.producedAnswerId(),
                run.producedPatchId(),
                run.producedSpecSnapshotId(),
                run.status().code(),
                traceSteps,
                progress,
                run.createdAt(),
                run.completedAt());
    }
}