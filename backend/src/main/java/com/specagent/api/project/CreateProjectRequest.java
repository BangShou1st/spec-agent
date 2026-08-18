package com.specagent.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-project request.
 *
 * <p>Only user-owned content is accepted. Runtime-owned fields
 * ({@code projectId}, {@code activeRouteId}, {@code defaultProfileId},
 * {@code createdAt}, {@code updatedAt}) are never accepted from clients.
 */
public record CreateProjectRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String title) {
}