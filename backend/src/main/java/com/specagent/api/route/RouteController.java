package com.specagent.api.route;

import com.specagent.api.common.ApiException;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Route read API. Phase 6.1 exposes route reads only; activate/fork/archive/
 * delete/restore/regenerate belongs to Phase 6.2 and is not present here.
 * Reads never mutate route lifecycle.
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