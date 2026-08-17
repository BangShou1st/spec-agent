package com.specagent.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable request handed to a model adapter for one agent reasoning step.
 *
 * <p>Every fake model run must carry a contextSnapshotId: the agent always
 * reasons against a frozen context snapshot, never against live state.
 *
 * <p>Metadata is runtime-owned context for the gateway. It carries no action
 * expectation: the model proposes its own action in its output, and the runtime
 * validates the proposal against the action the task requires.
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