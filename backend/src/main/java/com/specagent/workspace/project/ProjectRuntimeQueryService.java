package com.specagent.workspace.project;

import com.specagent.workspace.node.NodeResponse;
import com.specagent.workspace.route.RouteResponse;
import com.specagent.common.ApiException;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:ProjectRuntimeQueryService.java
 *
 * 用途:应用层查询组件,组合既有的运行时读取,产出运行时可见的
 * 活跃项目状态。
 *
 * 它组合 {@link ProjectService}、{@link RouteService} 与
 * {@link NodeService};绝不写状态、绝不调用模型、绝不构建
 * {@code ContextSnapshot},也绝不重新实现路线或上下文语义。
 * 它不是第二个 Runtime Kernel。
 *
 * 它是应用层读模型(其错误内核与视图 DTO 由应用层持有),因此放在
 * {@code com.specagent.workspace.project} 而不是 {@code api} 包。
 */
@Service
public class ProjectRuntimeQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;

    public ProjectRuntimeQueryService(ProjectService projectService,
                                      RouteService routeService,
                                      NodeService nodeService) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
    }

    public ActiveProjectStateResponse getActiveState(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));

        if (project.activeRouteId() == null) {
            return new ActiveProjectStateResponse(ProjectResponse.from(project), null, null);
        }

        Route activeRoute = routeService.getRoute(project.activeRouteId())
                .orElseThrow(() -> ApiException.internal("INTERNAL_INVARIANT_VIOLATION",
                        "The active route pointer does not resolve"));
        // 防御性 fail-closed 守卫:在正确的运行时不变量下,活跃指针必然
        // 解析到一条属于本项目的路线。一旦不是,外来路线及其节点都不得
        // 外露;读取以内部不变量违例失败。
        if (!activeRoute.projectId().equals(project.id())) {
            throw ApiException.internal("INTERNAL_INVARIANT_VIOLATION",
                    "The active route does not belong to the project");
        }
        RouteResponse routeResponse = RouteResponse.from(activeRoute, true);

        NodeResponse nodeResponse = null;
        if (activeRoute.tipNodeId() != null) {
            Node tip = nodeService.getNode(activeRoute.tipNodeId())
                    .orElseThrow(() -> ApiException.internal("INTERNAL_INVARIANT_VIOLATION",
                            "The route tip node does not resolve"));
            nodeResponse = NodeResponse.from(tip);
        }

        return new ActiveProjectStateResponse(ProjectResponse.from(project), routeResponse, nodeResponse);
    }
}
