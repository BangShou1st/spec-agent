package com.specagent.api.requirement;

import com.specagent.readmodel.requirement.RequirementStateQueryService;
import com.specagent.readmodel.requirement.RequirementStateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only route-scoped requirement-state API.
 *
 * <p>Exposes the backend-derived requirement state for an explicitly named
 * route (open, superseded, archived, or deleted) through the read-model query
 * boundary. This endpoint is read-only: it never calls a model, never writes
 * state, and never persists an answer, patch, node, route, or spec. The
 * existing active-route endpoint ({@link RequirementStateController}) is left
 * unchanged.
 *
 * <p>Architecture boundary (the API layer still never depends on context,
 * model, repository, or credential):
 *
 * <pre>
 * RouteRequirementStateController
 *         ↓
 * com.specagent.readmodel.requirement.RequirementStateQueryService
 *         ↓
 * ProjectService / RouteService / RequirementStateBuilder
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/routes/{routeId}/requirement-state")
public class RouteRequirementStateController {

    private final RequirementStateQueryService requirementStateQueryService;

    public RouteRequirementStateController(RequirementStateQueryService requirementStateQueryService) {
        this.requirementStateQueryService = requirementStateQueryService;
    }

    @GetMapping
    public RequirementStateView getRequirementState(@PathVariable UUID projectId,
                                                    @PathVariable UUID routeId) {
        return requirementStateQueryService.getForRoute(projectId, routeId);
    }
}
