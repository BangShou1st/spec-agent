package com.specagent.agent.decision;

import com.specagent.agent.decision.AgentTaskType;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ModelResponse.java
 *
 * 用途:模型适配器针对一次 Agent 推理步骤返回的不可变响应。
 *
 * 响应会回显请求方的 agentRunId 和 contextSnapshotId,使 Agent 循环总能
 * 把一条响应归因到产生它的那个确切 run 和快照。
 */
public record ModelResponse(
        UUID requestAgentRunId,
        UUID requestContextSnapshotId,
        AgentTaskType taskType,
        AgentAction action,
        String outputJson,
        Map<String, String> trace
) {
    public ModelResponse {
        if (requestAgentRunId == null) {
            throw new IllegalArgumentException("requestAgentRunId is required");
        }
        if (requestContextSnapshotId == null) {
            throw new IllegalArgumentException("requestContextSnapshotId is required");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("taskType is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        outputJson = outputJson == null ? "{}" : outputJson;
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }
}