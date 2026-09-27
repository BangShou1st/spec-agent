package com.specagent.workspace.route;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:Route.java
 *
 * 用途:显式的探索路线:对节点父链(lineage)的一个视图,附带
 * 生命周期状态。路线不是节点内容的来源;它只指向根节点(root)和
 * 尾节点(tip),并携带生命周期元数据。当前活跃路线由
 * {@code Project.activeRouteId} 标识,绝不通过
 * {@code lifecycleStatus == active} 来判断。
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
    private final RouteBranchType branchType;
    private final UUID sourceRouteId;
    private final UUID branchAtNodeId;
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
        this(id, projectId, rootNodeId, tipNodeId, lifecycleStatus, label,
                createdFromNodeId, supersedesRouteId, replacementOfNodeId,
                createdByRunId, null, null, null, createdAt, updatedAt);
    }

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
                 RouteBranchType branchType,
                 UUID sourceRouteId,
                 UUID branchAtNodeId,
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
        this.branchType = branchType;
        this.sourceRouteId = sourceRouteId;
        this.branchAtNodeId = branchAtNodeId;
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

    public RouteBranchType branchType() {
        return branchType;
    }

    public UUID sourceRouteId() {
        return sourceRouteId;
    }

    public UUID branchAtNodeId() {
        return branchAtNodeId;
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
