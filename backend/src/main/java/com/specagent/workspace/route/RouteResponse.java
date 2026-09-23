package com.specagent.workspace.route;

import com.specagent.workspace.route.Route;

import java.time.Instant;
import java.util.UUID;

/**
 * Route representation returned by the route reads and commands.
 *
 * <p>Owned by the application layer (use-case view model), serialized as-is by
 * the REST boundary. {@code lifecycleStatus} exposes the route lifecycle only
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
        String branchType,
        UUID sourceRouteId,
        UUID branchAtNodeId,
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
                route.branchType() == null ? null : route.branchType().code(),
                route.sourceRouteId(),
                route.branchAtNodeId(),
                route.createdAt(),
                route.updatedAt(),
                isActive);
    }
}
