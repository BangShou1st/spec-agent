package com.specagent.workspace.project;

import com.specagent.workspace.project.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * Lean project summary for list endpoints.
 */
public record ProjectSummaryResponse(
        UUID id,
        String title,
        UUID activeRouteId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectSummaryResponse from(Project project) {
        return new ProjectSummaryResponse(
                project.id(),
                project.title(),
                project.activeRouteId(),
                project.createdAt(),
                project.updatedAt());
    }
}