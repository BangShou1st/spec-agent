package com.specagent.workspace.route;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.ReadModelLineageWalker;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:RouteLineageQueryService.java
 *
 * 用途:解析一条路线历史节点 lineage 用于展示的薄读模型桥。这是
 * 路线工作区唯一新增的 UI 支撑读桥:它组合既有的运行时读取,绝不写状态、
 * 绝不调用模型、绝不构建或持久化 {@code ContextSnapshot},也绝不重新
 * 实现路线或上下文语义。它不是第二个 Runtime Kernel。
 *
 * lineage 语义:从 {@code tipNodeId} 沿 {@code parentNodeId} 指针向上
 * 走到根,再按根到尾的顺序返回。任何生命周期状态(open、superseded、
 * archived、deleted)都允许读取。fail-closed 策略:节点缺失、节点属于
 * 其他项目、lineage 成环或超过最大深度、路线记录的根节点与解析出的
 * lineage 不一致,都以内部不变量违例失败。没有 tip 节点的路线返回
 * 空节点列表。
 *
 * replacement 路线天然展示其父 lineage 加上 replacement 节点;
 * 不会仅因 {@code supersedesNodeId} 指向某个被取代节点,就把它注入
 * replacement 路线的 lineage。
 */
@Service
public class RouteLineageQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;

    public RouteLineageQueryService(ProjectService projectService,
                                    RouteService routeService,
                                    NodeService nodeService) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
    }

    public RouteLineageView getForRoute(UUID projectId, UUID routeId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.ROUTE_NOT_FOUND, "Route not found"));
        if (!route.projectId().equals(projectId)) {
            // 在 API 边界上,"别人的路线"与"不存在的路线"不可区分;
            // 两者都以 404 ROUTE_NOT_FOUND 呈现。
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.ROUTE_NOT_FOUND, "Route not found");
        }

        if (route.tipNodeId() == null) {
            return RouteLineageView.empty(project.id(), route.id(), route.rootNodeId(),
                    route.lifecycleStatus().code(), route.isActive(project.activeRouteId()));
        }

        List<RouteLineageNodeView> rootToTip = resolveLineage(project.id(), route);
        return new RouteLineageView(project.id(), route.id(), route.rootNodeId(), route.tipNodeId(),
                route.lifecycleStatus().code(), route.isActive(project.activeRouteId()), rootToTip);
    }

    private List<RouteLineageNodeView> resolveLineage(UUID projectId, Route route) {
        List<Node> rootToTip;
        try {
            rootToTip = ReadModelLineageWalker.walk(route.tipNodeId(), nodeService::getNode);
        } catch (ReadModelLineageWalker.LineageTraversalException ex) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION, ex.getMessage());
        }

        for (Node node : rootToTip) {
            if (!node.projectId().equals(projectId)) {
                // Fail closed:外来节点以及它之后的所有节点
                // 都不允许出现在响应中。
                throw RouteLineageQueryException.of(
                        RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                        "A node in the route lineage belongs to another project");
            }
        }

        List<RouteLineageNodeView> views = rootToTip.stream()
                .map(RouteLineageNodeView::from)
                .toList();

        if (route.rootNodeId() == null) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                    "Route has a tip node but no root node");
        }
        if (!route.rootNodeId().equals(views.get(0).id())) {
            throw RouteLineageQueryException.of(
                    RouteLineageQueryException.Reason.INVARIANT_VIOLATION,
                    "Route root node does not match the resolved lineage");
        }
        return List.copyOf(views);
    }
}
