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
    private final String operation;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Instant createdAt;
    private final Instant completedAt;
    private final UUID parentRunId;
    private final UUID rootRunId;
    private final Integer cycleIndex;

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
                    String operation,
                    String idempotencyKey,
                    String requestFingerprint,
                    Instant createdAt,
                    Instant completedAt) {
        this(id, projectId, routeId, triggerType, inputNodeId, contextSnapshotId,
                producedNodeId, producedAnswerId, producedPatchId, producedSpecSnapshotId,
                status, trace, operation, idempotencyKey, requestFingerprint,
                createdAt, completedAt, null, null, null);
    }

    /**
     * Full constructor including autonomous continuation chain linkage.
     *
     * <p>{@code parentRunId} names the run whose terminal boundary spawned
     * this one; {@code rootRunId} names the chain head for querying;
     * {@code cycleIndex} counts the child depth with chain roots at 0. All
     * three stay null for pre-continuation and external runs (lazy chain
     * identity: null reads as "own root at cycle 0").
     *
     * <p>This linkage is unrelated to the legacy {@code createdByRunId}
     * creation hint carried by {@code AgentRunService.create} overloads,
     * which is not persisted on the run row. The two concepts must never
     * be merged.
     */
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
                    String operation,
                    String idempotencyKey,
                    String requestFingerprint,
                    Instant createdAt,
                    Instant completedAt,
                    UUID parentRunId,
                    UUID rootRunId,
                    Integer cycleIndex) {
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
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.parentRunId = parentRunId;
        this.rootRunId = rootRunId;
        this.cycleIndex = cycleIndex;
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

    public String operation() {
        return operation;
    }

    /**
     * Stable client-supplied idempotency identity for create-run retries
     * (null for internally generated runs). Two creates with the same key in
     * the same project resolve to exactly one persisted run.
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * SHA-256 of the canonical logical request for client-idempotent runs.
     * Historical rows may legitimately have no fingerprint.
     */
    public String requestFingerprint() {
        return requestFingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    /**
     * The run whose terminal boundary spawned this run, or null for chain
     * roots and pre-continuation rows.
     */
    public UUID parentRunId() {
        return parentRunId;
    }

    /**
     * The head of this autonomous continuation chain for querying, or null
     * when the chain identity was never persisted (reads as this run).
     */
    public UUID rootRunId() {
        return rootRunId;
    }

    /**
     * Child depth in the chain with roots at 0, or null for
     * pre-continuation rows (reads as 0).
     */
    public Integer cycleIndex() {
        return cycleIndex;
    }
}
