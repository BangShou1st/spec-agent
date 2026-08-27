package com.specagent.api.route;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Fresh state after a route mutation command: the affected route as it now
 * stands and the project's current active-route pointer. {@code activeRouteId}
 * is {@code null} when the mutation cleared the active pointer (for example
 * archiving the previously active route).
 *
 * <p>{@code resumedNewRoute} is only meaningful for the resume command:
 * {@code true} when a new branch route was created, {@code false} when the
 * existing source route was merely reactivated. Other commands leave it
 * {@code null}.
 */
public record RouteMutationResponse(
        UUID projectId,
        RouteResponse route,
        UUID activeRouteId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean resumedNewRoute) {
}