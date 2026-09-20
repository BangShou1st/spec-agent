package com.specagent.route;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-side port for the project row lock and the active-route pointer.
 *
 * <p>Route lifecycle commands must serialize on the project row and maintain
 * {@code Project.activeRouteId}, but the route domain depending on the project
 * repository (while project creation depends on route creation) closes the
 * project &lt;-&gt; route package cycle. This port inverts the route -&gt;
 * project edge: the route domain speaks only in terms of locks, pointer
 * updates, and active-route reads. Implemented by the project-side
 * {@code ProjectRepository}.
 */
public interface ProjectActiveRoutePort {

    /** Blocks until this project's row lock is held (FOR UPDATE). */
    void lockProject(UUID projectId);

    /** Updates the active-route pointer and bumps the project's updated_at. */
    void updateActiveRoute(UUID projectId, UUID routeId, Instant updatedAt);

    /**
     * Current active-route pointer; empty when no route is active.
     * A missing project is a hard error and throws {@code IllegalArgumentException}.
     */
    Optional<UUID> findActiveRouteId(UUID projectId);
}
