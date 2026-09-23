package com.specagent.workspace.route;

import com.specagent.workspace.route.RouteCommandService;
import com.specagent.workspace.route.RouteMutationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Route command API. Commands go through {@link RouteCommandService} and the
 * existing {@link com.specagent.workspace.route.RouteService}; the controller never
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

    /** Starts a NEW standalone route from a floating knowledge/resource node
     * ("想法继续生成问题"): the node becomes the route's root+tip and the
     * next question draft anchors there. */
    @PostMapping("/nodes/{nodeId}/start-route")
    public RouteMutationResponse startRoute(@PathVariable UUID projectId,
                                            @PathVariable UUID nodeId,
                                            @Valid @RequestBody StartRouteFromNodeRequest request) {
        return routeCommandService.startRouteFromNode(projectId, nodeId, request.label());
    }
}
