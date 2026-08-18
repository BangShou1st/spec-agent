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
                                      @Valid @RequestBody(required = false) ForkRouteRequest request) {
        return routeCommandService.fork(projectId, nodeId,
                request == null ? null : request.label());
    }

    @PostMapping("/nodes/{nodeId}/regenerate")
    public RegenerateResponse regenerate(@PathVariable UUID projectId,
                                         @PathVariable UUID nodeId,
                                         @Valid @RequestBody RegenerateNodeRequest request) {
        return routeCommandService.regenerate(projectId, nodeId, request);
    }
}