package com.specagent.agent.gates;

import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validator for a context snapshot before any agent step runs.
 *
 * <p>A context may only be used when its route exists, belongs to the context
 * project, and is OPEN (or SUPERSEDED for explicit replacement exploration).
 * Normal (non-regenerate) context must also match the
 * project's active route; regenerate context is a special operation context and
 * therefore exempt from the active-route match.
 */
@Component
public class ContextGuard {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;

    public ContextGuard(ProjectRepository projectRepository, RouteRepository routeRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
    }

    public ReflectionResult validate(ContextSnapshot snapshot) {
        List<String> errors = new ArrayList<>();

        if (snapshot == null) {
            return ReflectionResult.rejectedResult("Context snapshot is required");
        }

        Project project = projectRepository.findById(snapshot.projectId()).orElse(null);
        if (project == null) {
            errors.add("Context project does not exist: " + snapshot.projectId());
        }

        Route route = routeRepository.findById(snapshot.routeId()).orElse(null);
        if (route == null) {
            errors.add("Context route does not exist: " + snapshot.routeId());
        } else {
            if (!route.projectId().equals(snapshot.projectId())) {
                errors.add("Context route does not belong to context project");
            }
            boolean replacementSource = snapshot.operationType() == ContextOperationType.REGENERATE;
            boolean validLifecycle = route.lifecycleStatus() == RouteLifecycleStatus.OPEN
                    || (replacementSource && route.lifecycleStatus() == RouteLifecycleStatus.SUPERSEDED);
            if (!validLifecycle) {
                errors.add(replacementSource
                        ? "Replacement context route must be OPEN or SUPERSEDED"
                        : "Context route must be OPEN");
            }
        }

        if (snapshot.operationType() != ContextOperationType.REGENERATE) {
            if (project != null) {
                if (project.activeRouteId() == null) {
                    errors.add("Normal context requires project active route");
                } else if (!project.activeRouteId().equals(snapshot.routeId())) {
                    errors.add("Normal context route must match project active route");
                }
            }
        }

        if (snapshot.contextHash() == null || snapshot.contextHash().isBlank()) {
            errors.add("Context hash is required");
        }

        if (errors.isEmpty()) {
            return ReflectionResult.acceptedResult();
        }
        return new ReflectionResult(false, errors, List.of());
    }
}
