package com.specagent.api.route;

import com.specagent.readmodel.route.RouteLineageQueryService;
import com.specagent.readmodel.route.RouteLineageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only route-lineage API.
 *
 * <p>Exposes one route's historical node chain through the read-model query
 * boundary. This endpoint is read-only, provider-free, model-free, and
 * persistence-free: it only inspects an existing route for display. It is the
 * only backend feature added in Phase 7.2; it never builds a
 * {@code ContextSnapshot} and is never used to change runtime semantics.
 *
 * <p>Architecture boundary (the API layer still never depends on context,
 * model, repository, or credential):
 *
 * <pre>
 * RouteLineageController
 *         ↓
 * com.specagent.readmodel.route.RouteLineageQueryService
 *         ↓
 * ProjectService / RouteService / NodeService
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes/{routeId}")
public class RouteLineageController {

    private final RouteLineageQueryService routeLineageQueryService;

    public RouteLineageController(RouteLineageQueryService routeLineageQueryService) {
        this.routeLineageQueryService = routeLineageQueryService;
    }

    @GetMapping("/lineage")
    public RouteLineageView getLineage(@PathVariable UUID projectId,
                                       @PathVariable UUID routeId) {
        return routeLineageQueryService.getForRoute(projectId, routeId);
    }
}
