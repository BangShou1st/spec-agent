package com.specagent.workspace.route;

import com.specagent.workspace.route.Route;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:RouteResponse.java
 *
 * 用途:路线读取与命令返回的路线表示。由应用层持有(用例视图模型),
 * REST 边界原样序列化。{@code lifecycleStatus} 只暴露路线生命周期
 * ({@code open|superseded|archived|deleted}),不存在 {@code active} 这种
 * 生命周期状态;{@code isActive} 在读取时通过路线 id 与
 * {@code Project.activeRouteId} 比对推导,绝不修改路线状态。
 */
public record RouteResponse(
        UUID id,
        UUID projectId,
        UUID rootNodeId,
        UUID tipNodeId,
        String lifecycleStatus,
        String label,
        UUID createdFromNodeId,
        UUID supersedesRouteId,
        UUID replacementOfNodeId,
        String branchType,
        UUID sourceRouteId,
        UUID branchAtNodeId,
        Instant createdAt,
        Instant updatedAt,
        boolean isActive) {

    public static RouteResponse from(Route route, boolean isActive) {
        return new RouteResponse(
                route.id(),
                route.projectId(),
                route.rootNodeId(),
                route.tipNodeId(),
                route.lifecycleStatus().code(),
                route.label(),
                route.createdFromNodeId(),
                route.supersedesRouteId(),
                route.replacementOfNodeId(),
                route.branchType() == null ? null : route.branchType().code(),
                route.sourceRouteId(),
                route.branchAtNodeId(),
                route.createdAt(),
                route.updatedAt(),
                isActive);
    }
}
