package com.specagent.workspace.route;

import java.util.UUID;

/**
 * 文件名:RouteMutationResponse.java
 *
 * 用途:路线变更命令后的最新状态响应:受影响路线的当前状态,以及
 * 项目当前的活跃路线指针。当变更清空了活跃指针时(例如归档了原本
 * 活跃的路线),{@code activeRouteId} 为 {@code null}。
 */
public record RouteMutationResponse(
        UUID projectId,
        RouteResponse route,
        UUID activeRouteId) {
}
