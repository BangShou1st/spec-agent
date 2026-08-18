package com.specagent.api.route;

import jakarta.validation.constraints.Size;

/**
 * Fork request. Only user-controlled metadata is accepted; runtime-owned
 * fields (routeId, rootNodeId, tipNodeId, createdFromNodeId, activeRouteId,
 * lifecycleStatus) can never be supplied.
 */
public record ForkRouteRequest(
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}