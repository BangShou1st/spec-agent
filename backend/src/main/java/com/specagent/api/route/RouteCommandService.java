package com.specagent.api.route;

import com.specagent.api.common.ApiException;
import com.specagent.api.common.CommandExecution;
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

import java.util.List;
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

    public RouteMutationResponse fork(UUID projectId, UUID nodeId, String label) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            CommandExecution.requireNodeInProject(projectService, nodeService, projectId, nodeId);
            Route fork = routeService.forkFromNode(projectId, nodeId, label);
            return refresh(projectId, fork.id());
        });
    }

    public RegenerateResponse regenerate(UUID projectId, UUID nodeId, RegenerateNodeRequest request) {
        return CommandExecution.execute(() -> {
            CommandExecution.requireProject(projectService, projectId);
            Node target = CommandExecution.requireNodeInProject(
                    projectService, nodeService, projectId, nodeId);
            if (target.parentNodeId() == null) {
                throw ApiException.conflict("REGENERATE_ROOT_NOT_SUPPORTED",
                        "Root node regeneration is not supported");
            }
            validateReplacementOptions(request);
            List<NodeOption> replacementOptions = request.replacementOptions() == null ? List.of()
                    : request.replacementOptions().stream()
                            .map(option -> NodeOption.of(option.label(), option.impact()))
                            .toList();
            RegenerateResult result = routeService.regenerateFromNode(
                    projectId,
                    nodeId,
                    request.instruction(),
                    request.replacementQuestion(),
                    request.replacementPurpose(),
                    replacementOptions);
            return new RegenerateResponse(
                    projectId,
                    RouteResponse.from(result.oldRoute(), false),
                    RouteResponse.from(result.replacementRoute(), true),
                    NodeResponse.from(result.replacementNode()));
        });
    }

    private void validateReplacementOptions(RegenerateNodeRequest request) {
        if (request.replacementOptions() == null) {
            return;
        }
        for (ReplacementOptionRequest option : request.replacementOptions()) {
            if (option == null || option.label() == null || option.label().isBlank()) {
                throw ApiException.badRequest("INVALID_REPLACEMENT_OPTION",
                        "Replacement option label must not be blank");
            }
        }
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