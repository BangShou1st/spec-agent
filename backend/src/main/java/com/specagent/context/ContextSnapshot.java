package com.specagent.context;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The exact lineage context used for one agent run.
 *
 * <p>Built deterministically from the active route's tip by replaying the parent
 * lineage. Sibling, superseded, archived, and deleted routes are excluded by
 * default and recorded in {@code excludedRouteIds}. This is derived context, not
 * source of truth.
 */
public class ContextSnapshot {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID tipNodeId;
    private final ContextOperationType operationType;
    private final List<UUID> includedNodeIds;
    private final List<UUID> includedAnswerIds;
    private final List<UUID> includedPatchIds;
    private final List<UUID> excludedRouteIds;
    private final String specialInputs;
    private final String contextHash;
    private final Instant createdAt;

    public ContextSnapshot(UUID id,
                           UUID projectId,
                           UUID routeId,
                           UUID tipNodeId,
                           ContextOperationType operationType,
                           List<UUID> includedNodeIds,
                           List<UUID> includedAnswerIds,
                           List<UUID> includedPatchIds,
                           List<UUID> excludedRouteIds,
                           String specialInputs,
                           String contextHash,
                           Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.tipNodeId = tipNodeId;
        this.operationType = operationType;
        this.includedNodeIds = includedNodeIds == null ? List.of() : List.copyOf(includedNodeIds);
        this.includedAnswerIds = includedAnswerIds == null ? List.of() : List.copyOf(includedAnswerIds);
        this.includedPatchIds = includedPatchIds == null ? List.of() : List.copyOf(includedPatchIds);
        this.excludedRouteIds = excludedRouteIds == null ? List.of() : List.copyOf(excludedRouteIds);
        this.specialInputs = specialInputs;
        this.contextHash = contextHash;
        this.createdAt = createdAt;
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

    public UUID tipNodeId() {
        return tipNodeId;
    }

    public ContextOperationType operationType() {
        return operationType;
    }

    public List<UUID> includedNodeIds() {
        return includedNodeIds;
    }

    public List<UUID> includedAnswerIds() {
        return includedAnswerIds;
    }

    public List<UUID> includedPatchIds() {
        return includedPatchIds;
    }

    public List<UUID> excludedRouteIds() {
        return excludedRouteIds;
    }

    public String specialInputs() {
        return specialInputs;
    }

    public String contextHash() {
        return contextHash;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
