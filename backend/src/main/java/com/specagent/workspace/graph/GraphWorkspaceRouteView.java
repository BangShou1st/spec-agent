package com.specagent.workspace.graph;

import com.specagent.workspace.route.Route;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceRouteView.java
 *
 * 用途:项目图上单条路线的只读视图。{@code lifecycleStatus} 只表示
 * 路线生命周期({@code open|superseded|archived|deleted});{@code isActive}
 * 在读取时由 {@code Project.activeRouteId} 推导,绝不改动路线状态。
 * {@code lineageNodeIds} 是该路线从根到 tip 的权威节点成员表。
 * 替换元数据({@code supersedesRouteId}、{@code replacementOfNodeId})
 * 仅用于展示,绝不会把被取代的目标注入 lineage。
 */
public record GraphWorkspaceRouteView(
        UUID id,
        String label,
        String lifecycleStatus,
        boolean isActive,
        UUID rootNodeId,
        UUID tipNodeId,
        UUID createdFromNodeId,
        UUID supersedesRouteId,
        UUID replacementOfNodeId,
        String branchType,
        UUID sourceRouteId,
        UUID branchAtNodeId,
        List<UUID> lineageNodeIds) {

    public static GraphWorkspaceRouteView from(
            Route route, UUID activeRouteId, List<UUID> lineageNodeIds) {
        return new GraphWorkspaceRouteView(
                route.id(), route.label(), route.lifecycleStatus().code(),
                route.isActive(activeRouteId), route.rootNodeId(), route.tipNodeId(),
                route.createdFromNodeId(), route.supersedesRouteId(),
                route.replacementOfNodeId(),
                route.branchType() == null ? null : route.branchType().code(),
                route.sourceRouteId(), route.branchAtNodeId(), List.copyOf(lineageNodeIds));
    }
}
