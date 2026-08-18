package com.specagent.api.requirement;

import com.specagent.readmodel.requirement.RequirementStateQueryService;
import com.specagent.readmodel.requirement.RequirementStateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only requirement-state API.
 *
 * <p>Exposes the backend-derived requirement state for the project's active
 * route through the read-model query boundary. This endpoint is read-only: it
 * never calls a model and never persists an answer, patch, node, route, or
 * spec. RequirementState remains derived and cacheable, never source of truth.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirement-state")
public class RequirementStateController {

    private final RequirementStateQueryService requirementStateQueryService;

    public RequirementStateController(RequirementStateQueryService requirementStateQueryService) {
        this.requirementStateQueryService = requirementStateQueryService;
    }

    @GetMapping
    public RequirementStateView getRequirementState(@PathVariable UUID projectId) {
        return requirementStateQueryService.getForProject(projectId);
    }
}