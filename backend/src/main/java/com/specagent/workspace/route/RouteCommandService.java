package com.specagent.workspace.route;

import com.specagent.workspace.route.CommandExecution;
import com.specagent.common.ApiException;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:RouteCommandService.java
 *
 * 用途:路线变更命令的应用层组合服务。所有路线命令最终都经由
 * {@link RouteService} 执行;本服务只做"可读状态的前置校验"以产生精确的
 * API 错误,并把预期内的运行时失败翻译成安全的 API 错误。它自身绝不写
 * 数据库状态,也绝不重新实现路线语义。
 *
 * 它是应用层用例(由运行时切片服务与共享错误内核组合而成),因此
 * 放在 {@code com.specagent.workspace.route} 而不是 {@code api} 包;
 * REST 控制器只是它上面的一层薄翻译层。
 */
@Service
public class RouteCommandService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;

    public RouteCommandService(ProjectService projectService,
                               RouteService routeService,
                               NodeService nodeService) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
    }

    public RouteMutationResponse activate(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            Project project = CommandExecution.requireProject(projectService, projectId);
            Route route = CommandExecution.requireRouteInProject(
                    projectService, routeService, projectId, routeId);
            if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
                throw ApiException.conflict("ROUTE_NOT_ACTIVATABLE",
                        "Only an OPEN route can be activated");
            }
            routeService.setActiveRoute(projectId, routeId);
            return refresh(projectId, routeId);
        });
    }

    public RouteMutationResponse archive(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.archiveRoute(projectId, routeId);
            return refresh(projectId, routeId);
        });
    }

    public RouteMutationResponse restore(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.restoreRoute(projectId, routeId);
            return refresh(projectId, routeId);
        });
    }

    public RouteMutationResponse softDelete(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.softDeleteRoute(projectId, routeId);
            return refresh(projectId, routeId);
        });
    }

    public RouteMutationResponse fork(UUID projectId, UUID nodeId, UUID sourceRouteId, String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, sourceRouteId);
            Route fork = routeService.forkFromNode(projectId, sourceRouteId, nodeId, label);
            return refresh(projectId, fork.id());
        });
    }

    public RouteMutationResponse reanswer(UUID projectId,
                                          UUID nodeId,
                                          UUID sourceRouteId,
                                          String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, sourceRouteId);
            Route route = routeService.reanswerFromNode(projectId, sourceRouteId, nodeId, label);
            return refresh(projectId, route.id());
        });
    }

    public RouteMutationResponse startRouteFromNode(UUID projectId, UUID nodeId, String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            Route route = routeService.startRouteFromNode(projectId, nodeId, label);
            return refresh(projectId, route.id());
        });
    }

    private RouteMutationResponse refresh(UUID projectId, UUID routeId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
        return new RouteMutationResponse(
                projectId,
                RouteResponse.from(route, route.isActive(project.activeRouteId())),
                project.activeRouteId());
    }
}
