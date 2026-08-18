package com.specagent.api.project;

import com.specagent.project.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * Full project representation for the API boundary.
 *
 * <p>Runtime-owned fields are exposed read-only; none of them can be supplied
 * through a request. {@code activeRouteId} remains the only active-route
 * pointer and is never derived from a route lifecycle status.
 */
public record ProjectResponse(
        UUID id,
        String title,
        UUID activeRouteId,
        UUID defaultProfileId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.id(),
                project.title(),
                project.activeRouteId(),
                project.defaultProfileId(),
                project.createdAt(),
                project.updatedAt());
    }
}