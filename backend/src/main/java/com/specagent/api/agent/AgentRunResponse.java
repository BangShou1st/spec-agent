package com.specagent.api.agent;

import com.specagent.agent.AgentRun;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe operator read of an agent run.
 *
 * <p>Exposes run metadata and a safe trace-step list only. The stored trace is
 * a newline-joined sequence of diagnostic lifecycle steps (for example
 * {@code ["created", "context_built", "model_called:DRAFT_NODE", "completed"]});
 * it never carries API credentials, raw prompts, raw model/provider payloads,
 * or stack traces, and neither does this DTO. The controller decodes the
 * persisted trace into the step list before calling {@link #from}.
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
        Instant createdAt,
        Instant completedAt) {

    public static AgentRunResponse from(AgentRun run, List<String> traceSteps) {
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
                run.createdAt(),
                run.completedAt());
    }
}