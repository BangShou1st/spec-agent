package com.specagent.api.route;

import com.specagent.route.Route;

import java.time.Instant;
import java.util.UUID;

/**
 * Route representation for the API boundary.
 *
 * <p>{@code lifecycleStatus} exposes the route lifecycle only
 * ({@code open|superseded|archived|deleted}); there is no {@code active}
 * lifecycle status. {@code isActive} is derived by comparing the route id with
 * {@code Project.activeRouteId} at read time and never mutates route state.
 */
public record RouteResponse(
        UUID id,
        UUID projectId,
        UUID rootNodeId,
        UUID tipNodeId,
        String lifecycleStatus,
        String label,
        UUID createdFromNodeId,
        UUID supersedesRouteId,
        UUID replacementOfNodeId,
        Instant createdAt,
        Instant updatedAt,
        boolean isActive) {

    public static RouteResponse from(Route route, boolean isActive) {
        return new RouteResponse(
                route.id(),
                route.projectId(),
                route.rootNodeId(),
                route.tipNodeId(),
                route.lifecycleStatus().code(),
                route.label(),
                route.createdFromNodeId(),
                route.supersedesRouteId(),
                route.replacementOfNodeId(),
                route.createdAt(),
                route.updatedAt(),
                isActive);
    }
}