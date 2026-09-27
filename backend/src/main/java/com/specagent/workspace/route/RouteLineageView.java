package com.specagent.workspace.route;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:RouteLineageView.java
 *
 * 用途:给 UI 的路线 lineage 只读视图,按根到尾顺序描述一条既有路线
 * 及其历史节点链。{@code lifecycleStatus} 只是路线生命周期
 * ({@code open|superseded|archived|deleted});{@code isActive} 在读取时
 * 由 {@code Project.activeRouteId} 推导,绝不修改路线状态。
 * 路线没有 tip 节点时,{@code nodes} 为空列表。
 *
 * 这是纯展示读取:不用于改变 {@code ContextBuilder} 语义,
 * 也绝不构建或持久化 {@code ContextSnapshot}。
 */
public record RouteLineageView(
        UUID projectId,
        UUID routeId,
        UUID rootNodeId,
        UUID tipNodeId,
        String lifecycleStatus,
        boolean isActive,
        List<RouteLineageNodeView> nodes) {

    /** 没有 tip 节点的路线的安全读模型。 */
    public static RouteLineageView empty(UUID projectId, UUID routeId,
                                         UUID rootNodeId, String lifecycleStatus, boolean isActive) {
        return new RouteLineageView(projectId, routeId, rootNodeId, null,
                lifecycleStatus, isActive, List.of());
    }
}
