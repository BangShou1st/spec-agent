package com.specagent.route;

import java.time.Instant;
import java.util.UUID;

/**
 * Explicit exploration route: a view over node lineage with lifecycle state.
 *
 * <p>A route is not the source of all node content; it points to a root and tip
 * node and carries lifecycle metadata. The active route is identified by
 * {@code Project.activeRouteId}, never by {@code lifecycleStatus == active}.
 */
public class Route {

    private final UUID id;
    private final UUID projectId;
    private final UUID rootNodeId;
    private final UUID tipNodeId;
    private final RouteLifecycleStatus lifecycleStatus;
    private final String label;
    private final UUID createdFromNodeId;
    private final UUID supersedesRouteId;
    private final UUID replacementOfNodeId;
    private final UUID createdByRunId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Route(UUID id,
                 UUID projectId,
                 UUID rootNodeId,
                 UUID tipNodeId,
                 RouteLifecycleStatus lifecycleStatus,
                 String label,
                 UUID createdFromNodeId,
                 UUID supersedesRouteId,
                 UUID replacementOfNodeId,
                 UUID createdByRunId,
                 Instant createdAt,
                 Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.rootNodeId = rootNodeId;
        this.tipNodeId = tipNodeId;
        this.lifecycleStatus = lifecycleStatus;
        this.label = label;
        this.createdFromNodeId = createdFromNodeId;
        this.supersedesRouteId = supersedesRouteId;
        this.replacementOfNodeId = replacementOfNodeId;
        this.createdByRunId = createdByRunId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID rootNodeId() {
        return rootNodeId;
    }

    public UUID tipNodeId() {
        return tipNodeId;
    }

    public RouteLifecycleStatus lifecycleStatus() {
        return lifecycleStatus;
    }

    public String label() {
        return label;
    }

    public UUID createdFromNodeId() {
        return createdFromNodeId;
    }

    public UUID supersedesRouteId() {
        return supersedesRouteId;
    }

    public UUID replacementOfNodeId() {
        return replacementOfNodeId;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isActive(UUID activeRouteId) {
        return activeRouteId != null && activeRouteId.equals(id);
    }

    public boolean isExcludedByDefault() {
        return lifecycleStatus == RouteLifecycleStatus.SUPERSEDED
                || lifecycleStatus == RouteLifecycleStatus.ARCHIVED
                || lifecycleStatus == RouteLifecycleStatus.DELETED;
    }
}
