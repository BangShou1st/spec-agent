package com.specagent.workspace.project;

import com.specagent.workspace.node.NodeResponse;
import com.specagent.workspace.route.RouteResponse;

/**
 * 文件名:ActiveProjectStateResponse.java
 *
 * 用途:运行时可见的活跃项目状态,概念上是
 * {@code {project, activeRoute, activeNode}}。{@code active} 只跟随
 * {@code Project.activeRouteId},它不是路线的生命周期状态。项目没有
 * 活跃路线时 {@code activeRoute} 为 {@code null};活跃路线存在但还没有
 * tip 节点时 {@code activeNode} 为 {@code null}。绝不凭空捏造初始节点。
 */
public record ActiveProjectStateResponse(
        ProjectResponse project,
        RouteResponse activeRoute,
        NodeResponse activeNode) {
}
