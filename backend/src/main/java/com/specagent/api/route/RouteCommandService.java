package com.specagent.api.route;

import com.specagent.api.common.ApiException;
import com.specagent.api.common.CommandExecution;
import com.specagent.agent.ReplacementRunResult;
import com.specagent.api.node.NodeResponse;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RegenerateResult;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Command composition for route mutations.
 *
 * <p>All route commands go through {@link RouteService}; this service only
 * pre-validates readable state for precise API errors and translates expected
 * runtime failures into safe API errors. It never writes database state itself
 * and never re-implements route semantics.
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
            return refresh(projectId, routeId, null);
        });
    }

    public RouteMutationResponse archive(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.archiveRoute(projectId, routeId);
            return refresh(projectId, routeId, null);
        });
    }

    public RouteMutationResponse restore(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.restoreRoute(projectId, routeId);
            return refresh(projectId, routeId, null);
        });
    }

    public RouteMutationResponse softDelete(UUID projectId, UUID routeId) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, routeId);
            routeService.softDeleteRoute(projectId, routeId);
            return refresh(projectId, routeId, null);
        });
    }

    public RouteMutationResponse fork(UUID projectId, UUID nodeId, UUID sourceRouteId, String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, sourceRouteId);
            Route fork = routeService.forkFromNode(projectId, sourceRouteId, nodeId, label);
            return refresh(projectId, fork.id(), null);
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
            return refresh(projectId, route.id(), null);
        });
    }

    /**
     * Reactivates a historical unanswered Question on an explicit source
     * route. If the source route is OPEN and its tip IS the target, only the
     * Active pointer is updated in place — no new route, no GraphOperation.
     * Otherwise a new RESUME_QUESTION branch route is created in a single
     * transactional boundary together with its inherited prefix snapshot and
     * the GraphOperation append.
     */
    public RouteMutationResponse resume(UUID projectId,
                                        UUID nodeId,
                                        UUID sourceRouteId,
                                        String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            CommandExecution.requireRouteInProject(projectService, routeService, projectId, sourceRouteId);

            // Atomic: new route + inherited prefix + Active switch + GraphOperation
            // append all happen inside RouteService.resumeAnsweringFromNode in one
            // @Transactional boundary. Undo/Redo is route-only and must never
            // retract the canonical Question.
            RouteService.ResumeQuestionResult result = routeService.resumeAnsweringFromNode(
                    projectId, sourceRouteId, nodeId, label);
            return refresh(projectId, result.route().id(), result.createdNewRoute());
        });
    }

    private RouteMutationResponse refresh(UUID projectId, UUID routeId, Boolean resumedNewRoute) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));
        Route route = routeService.getRoute(routeId)
                .orElseThrow(() -> ApiException.notFound("ROUTE_NOT_FOUND", "Route not found"));
        return new RouteMutationResponse(
                projectId,
                RouteResponse.from(route, route.isActive(project.activeRouteId())),
                project.activeRouteId(),
                resumedNewRoute);
    }
}
