package com.specagent.readmodel.requirement;

import com.specagent.api.common.ApiException;
import com.specagent.context.RequirementState;
import com.specagent.context.RequirementStateBuilder;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thin read-model bridge that derives the current requirement state for the
 * project's active route.
 *
 * <p>This is the only UI-support read bridge added for the first frontend: it
 * composes existing runtime reads and {@link RequirementStateBuilder}, and it
 * never writes state, never calls a model, never builds or persists a
 * {@code ContextSnapshot}, and never makes RequirementState the source of
 * truth. It is not a second Runtime Kernel.
 *
 * <p>When the project has no active route, a safe empty read model is returned
 * instead of inventing a route. If the active pointer ever fails to resolve to
 * a route owned by this project, the read fails closed as an internal
 * invariant violation so foreign data can never be exposed.
 */
@Service
public class RequirementStateQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final RequirementStateBuilder requirementStateBuilder;

    public RequirementStateQueryService(ProjectService projectService,
                                        RouteService routeService,
                                        RequirementStateBuilder requirementStateBuilder) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.requirementStateBuilder = requirementStateBuilder;
    }

    public RequirementStateView getForProject(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "Project not found"));

        if (project.activeRouteId() == null) {
            return RequirementStateView.empty(project.id());
        }

        Route activeRoute = routeService.getRoute(project.activeRouteId())
                .orElseThrow(() -> ApiException.internal("INTERNAL_INVARIANT_VIOLATION",
                        "The active route pointer does not resolve"));
        // Defensive fail-closed guard: under correct runtime invariants the
        // active pointer always resolves to a route owned by this project. If
        // it ever does not, neither the foreign route nor its claims may be
        // exposed; the read fails as an internal invariant violation.
        if (!activeRoute.projectId().equals(project.id())) {
            throw ApiException.internal("INTERNAL_INVARIANT_VIOLATION",
                    "The active route does not belong to the project");
        }

        RequirementState state = requirementStateBuilder.buildForRoute(project.id(), activeRoute.id());
        return RequirementStateView.from(project.id(), activeRoute.id(), state);
    }
}