package com.specagent.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable response from a model adapter for one agent reasoning step.
 *
 * <p>Echoes back the requesting agentRunId and contextSnapshotId so the agent
 * loop can always attribute a response to the exact run and snapshot it was
 * produced from.
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