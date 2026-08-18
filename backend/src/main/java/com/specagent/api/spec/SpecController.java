package com.specagent.api.spec;

import com.specagent.api.common.ApiException;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SpecSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Spec read API.
 *
 * <p>Snapshots are derived artifacts and are exposed read-only. Route-scoped
 * reads verify project ownership so a route from project A can never be read
 * through project B. Phase 6.1 does not implement spec generation.
 */
@RestController
public class SpecController {

    private final SpecSnapshotService specSnapshotService;
    private final ProjectService projectService;
    private final RouteService routeService;

    public SpecController(SpecSnapshotService specSnapshotService,
                          ProjectService projectService,
                          RouteService routeService) {
        this.specSnapshotService = specSnapshotService;
        this.projectService = projectService;
        this.routeService = routeService;
    }

    @GetMapping("/api/v1/specs/{snapshotId}")
    public SpecSnapshotResponse getSnapshot(@PathVariable UUID snapshotId) {
        return specSnapshotService.getSnapshot(snapshotId)
                .map(SpecSnapshotResponse::from)
                .orElseThrow(() -> ApiException.notFound("SPEC_NOT_FOUND", "Spec snapshot not found"));
    }

    @GetMapping("/api/v1/projects/{projectId}/routes/{routeId}/specs")
    public List<SpecSnapshotResponse> listRouteSpecs(@PathVariable UUID projectId,
                                                     @PathVariable UUID routeId) {
        projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
        if (!route.projectId().equals(projectId)) {
            throw ApiException.notFound("ROUTE_NOT_FOUND", "Route not found");
        }
        return specSnapshotService.listByRoute(routeId).stream()
                .map(SpecSnapshotResponse::from)
                .toList();
    }
}