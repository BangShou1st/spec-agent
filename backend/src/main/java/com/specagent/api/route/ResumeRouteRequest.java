package com.specagent.api.route;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Resume a historical unanswered Question request. Only user-controlled
 * metadata is accepted; runtime-owned fields (routeId, rootNodeId, tipNodeId,
 * activeRouteId, lifecycleStatus) can never be supplied.
 */
public record ResumeRouteRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
