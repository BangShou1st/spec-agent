package com.specagent.api.route;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Fork request. Only user-controlled metadata is accepted; runtime-owned
 * fields (routeId, rootNodeId, tipNodeId, createdFromNodeId, activeRouteId,
 * lifecycleStatus) can never be supplied.
 */
public record ForkRouteRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
