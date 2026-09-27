package com.specagent.workspace.route;

import com.specagent.workspace.route.RouteResponse;
import com.specagent.common.ApiException;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:RouteController.java
 *
 * 用途:路线读取 API。Phase 6.1 只暴露路线的读取;activate/fork/
 * archive/delete/restore/regenerate 属于 Phase 6.2,不在此处。
 * 读取操作绝不改变路线生命周期。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes")
public class RouteController {

    private final ProjectService projectService;
    private final RouteService routeService;

    public RouteController(ProjectService projectService, RouteService routeService) {
        this.projectService = projectService;
        this.routeService = routeService;
    }

    @GetMapping
    public List<RouteResponse> listRoutes(@PathVariable UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        return routeService.listRoutes(projectId).stream()
                .map(route -> RouteResponse.from(route, route.isActive(project.activeRouteId())))
                .toList();
    }
}