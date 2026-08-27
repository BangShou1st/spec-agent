package com.specagent.api.route;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Route command API. Commands go through {@link RouteCommandService} and the
 * existing {@link com.specagent.route.RouteService}; the controller never
 * writes database state and never mutates {@code Project.activeRouteId}
 * directly. Reads and commands never turn route lifecycle into
 * {@code active}.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class RouteCommandController {

    private final RouteCommandService routeCommandService;

    public RouteCommandController(RouteCommandService routeCommandService) {
        this.routeCommandService = routeCommandService;
    }

    @PostMapping("/routes/{routeId}/activate")
    public RouteMutationResponse activate(@PathVariable UUID projectId,
                                          @PathVariable UUID routeId) {
        return routeCommandService.activate(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/archive")
    public RouteMutationResponse archive(@PathVariable UUID projectId,
                                         @PathVariable UUID routeId) {
        return routeCommandService.archive(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/restore")
    public RouteMutationResponse restore(@PathVariable UUID projectId,
                                         @PathVariable UUID routeId) {
        return routeCommandService.restore(projectId, routeId);
    }

    @PostMapping("/routes/{routeId}/delete")
    public RouteMutationResponse delete(@PathVariable UUID projectId,
                                        @PathVariable UUID routeId) {
        return routeCommandService.softDelete(projectId, routeId);
    }

    @PostMapping("/nodes/{nodeId}/fork")
    public RouteMutationResponse fork(@PathVariable UUID projectId,
                                      @PathVariable UUID nodeId,
                                      @Valid @RequestBody ForkRouteRequest request) {
        return routeCommandService.fork(projectId, nodeId, request.sourceRouteId(), request.label());
    }

    @PostMapping("/nodes/{nodeId}/reanswer")
    public RouteMutationResponse reanswer(@PathVariable UUID projectId,
                                          @PathVariable UUID nodeId,
                                          @Valid @RequestBody ReanswerRouteRequest request) {
        return routeCommandService.reanswer(projectId, nodeId,
                request.sourceRouteId(), request.label());
    }

    /**
     * Reactivates a historical unanswered Question on the explicit source
     * route. If the source route is OPEN and its tip IS the target, the
     * existing source route is reactivated in place (no new route, no
     * GraphOperation). Otherwise a new RESUME_QUESTION branch route is
     * created in one transactional boundary together with the inherited
     * prefix snapshot, the Active pointer switch, and the
     * {@code RESUME_QUESTION_FROM_HISTORY} GraphOperation append.
     */
    @PostMapping("/nodes/{nodeId}/resume")
    public RouteMutationResponse resume(@PathVariable UUID projectId,
                                        @PathVariable UUID nodeId,
                                        @Valid @RequestBody ResumeRouteRequest request) {
        return routeCommandService.resume(projectId, nodeId,
                request.sourceRouteId(), request.label());
    }
}
