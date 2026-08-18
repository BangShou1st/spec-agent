package com.specagent.api.route;

import java.util.UUID;

/**
 * Fresh state after a route mutation command: the affected route as it now
 * stands and the project's current active-route pointer. {@code activeRouteId}
 * is {@code null} when the mutation cleared the active pointer (for example
 * archiving the previously active route).
 */
public record RouteMutationResponse(
        UUID projectId,
        RouteResponse route,
        UUID activeRouteId) {
}