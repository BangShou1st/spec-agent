package com.specagent.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * One controlled agent execution.
 *
 * <p>An agent run records which operation triggered it and which runtime records
 * it produced. It does not own persistent requirement state itself; the runtime
 * kernel remains the source of truth.
 */
public class AgentRun {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final AgentRunTriggerType triggerType;
    private final UUID inputNodeId;
    private final UUID contextSnapshotId;
    private final UUID producedNodeId;
    private final UUID producedAnswerId;
    private final UUID producedPatchId;
    private final UUID producedSpecSnapshotId;
    private final AgentRunStatus status;
    private final String trace;
    private final Instant createdAt;
    private final Instant completedAt;

    public AgentRun(UUID id,
                    UUID projectId,
                    UUID routeId,
                    AgentRunTriggerType triggerType,
                    UUID inputNodeId,
                    UUID contextSnapshotId,
                    UUID producedNodeId,
                    UUID producedAnswerId,
                    UUID producedPatchId,
                    UUID producedSpecSnapshotId,
                    AgentRunStatus status,
                    String trace,
                    Instant createdAt,
                    Instant completedAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.triggerType = triggerType;
        this.inputNodeId = inputNodeId;
        this.contextSnapshotId = contextSnapshotId;
        this.producedNodeId = producedNodeId;
        this.producedAnswerId = producedAnswerId;
        this.producedPatchId = producedPatchId;
        this.producedSpecSnapshotId = producedSpecSnapshotId;
        this.status = status;
        this.trace = trace;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID routeId() {
        return routeId;
    }

    public AgentRunTriggerType triggerType() {
        return triggerType;
    }

    public UUID inputNodeId() {
        return inputNodeId;
    }

    public UUID contextSnapshotId() {
        return contextSnapshotId;
    }

    public UUID producedNodeId() {
        return producedNodeId;
    }

    public UUID producedAnswerId() {
        return producedAnswerId;
    }

    public UUID producedPatchId() {
        return producedPatchId;
    }

    public UUID producedSpecSnapshotId() {
        return producedSpecSnapshotId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public String trace() {
        return trace;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
