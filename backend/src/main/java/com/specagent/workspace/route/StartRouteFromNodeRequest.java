package com.specagent.workspace.route;

import jakarta.validation.constraints.Size;

/**
 * Start-a-route-from-a-floating-node request. Only user-controlled metadata
 * is accepted; runtime-owned fields (routeId, rootNodeId, tipNodeId,
 * activeRouteId, lifecycleStatus) can never be supplied.
 */
public record StartRouteFromNodeRequest(
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
