package com.specagent.api.project;

import com.specagent.api.common.ApiException;
import com.specagent.api.node.NodeResponse;
import com.specagent.api.route.RouteResponse;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thin application query component that composes existing runtime reads to
 * produce the runtime-visible active project state.
 *
 * <p>It composes {@link ProjectService}, {@link RouteService}, and
 * {@link NodeService}; it never writes state, never calls the model, never
 * builds a {@code ContextSnapshot}, and never re-implements route or context
 * semantics. It is not a second Runtime Kernel.
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
        // Defensive fail-closed guard: under correct runtime invariants the
        // active pointer always resolves to a route owned by this project. If
        // it ever does not, neither the foreign route nor its node may be
        // exposed; the read fails as an internal invariant violation.
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