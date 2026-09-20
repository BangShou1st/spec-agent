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
 *
 * <p>Routeless NODE_QUERY context is first-class: a floating canonical Node
 * (routeIds=[]) carries {@code routeId == null}, and its snapshot is the
 * anchor node's own lineage. Such a snapshot must be validated against the
 * project and its context hash, but must never require a route, an active
 * route, or route-to-active matching. Every other operation type retains the
 * strict route requirements below.
 */
@Component
public class ContextGuard {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;

    public ContextGuard(ProjectRepository projectRepository, RouteRepository routeRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
    }

    /**
     * Default validation: the context route must be the project's Active route.
     * Kept as the single-argument entry point so every existing caller and the
     * whole active-route invariant suite is unaffected.
     */
    public ReflectionResult validate(ContextSnapshot snapshot) {
        return validate(snapshot, false);
    }

    /**
     * Validates a context snapshot.
     *
     * @param explicitRoute true when the run was created against an EXPLICIT
     *        route instead of the project Active route. Multi-route work
     *        (answering or drafting on a route that is not Active) is legal
     *        only in this mode, and even then the route must still belong to
     *        the project and be OPEN — the Active-equality check is the only
     *        rule that is skipped, never the lifecycle/ownership ones. Without
     *        an explicit route the behaviour is byte-identical to before.
     */
    public ReflectionResult validate(ContextSnapshot snapshot, boolean explicitRoute) {
        List<String> errors = new ArrayList<>();

        if (snapshot == null) {
            return ReflectionResult.rejectedResult("Context snapshot is required");
        }

        Project project = projectRepository.findById(snapshot.projectId()).orElse(null);
        if (project == null) {
            errors.add("Context project does not exist: " + snapshot.projectId());
        }

        boolean routelessNodeQuery = snapshot.operationType() == ContextOperationType.NODE_QUERY
                && snapshot.routeId() == null;
        if (routelessNodeQuery) {
            // Floating node query: the context is the anchor node itself and
            // references no route. Only project existence and the context hash
            // are required; route/active-route requirements never apply.
            if (snapshot.contextHash() == null || snapshot.contextHash().isBlank()) {
                errors.add("Context hash is required");
            }
            if (errors.isEmpty()) {
                return ReflectionResult.acceptedResult();
            }
            return new ReflectionResult(false, errors, List.of());
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

        if (snapshot.operationType() != ContextOperationType.REGENERATE && !explicitRoute) {
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
