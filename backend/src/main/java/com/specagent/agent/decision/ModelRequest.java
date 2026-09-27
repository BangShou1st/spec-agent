package com.specagent.agent.decision;

import com.specagent.agent.decision.AgentTaskType;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ModelRequest.java
 *
 * 用途:交给模型适配器、用于一次 Agent 推理步骤的不可变请求。
 *
 * 每次模型运行都必须携带 contextSnapshotId:Agent 始终基于冻结的上下文
 * 快照推理,绝不基于实时状态。
 *
 * metadata 是供网关使用的运行时上下文。它不携带任何动作预期:模型在
 * 输出中自行提出动作,运行时再对照该任务要求的动作去校验提案。
 */
public record ModelRequest(
        UUID projectId,
        UUID routeId,
        UUID agentRunId,
        UUID contextSnapshotId,
        AgentTaskType taskType,
        String inputJson,
        Map<String, String> metadata
) {

    public ModelRequest {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (routeId == null) {
            throw new IllegalArgumentException("routeId is required");
        }
        if (agentRunId == null) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (contextSnapshotId == null) {
            throw new IllegalArgumentException("contextSnapshotId is required");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("taskType is required");
        }
        inputJson = inputJson == null ? "{}" : inputJson;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
