package com.specagent.readmodel.route;

import java.util.List;
import java.util.UUID;

/**
 * Read-only route lineage view for the UI.
 *
 * <p>Describes one existing route and its historical node chain in root→tip
 * order. {@code lifecycleStatus} is the route lifecycle only
 * ({@code open|superseded|archived|deleted}); {@code isActive} is derived from
 * {@code Project.activeRouteId} at read time and never mutates route state.
 * When the route has no tip node, {@code nodes} is an empty list.
 *
 * <p>This is a display read. It is not used to change {@code ContextBuilder}
 * semantics and it never builds or persists a {@code ContextSnapshot}.
 */
public record RouteLineageView(
        UUID projectId,
        UUID routeId,
        UUID rootNodeId,
        UUID tipNodeId,
        String lifecycleStatus,
        boolean isActive,
        List<RouteLineageNodeView> nodes) {

    /** Safe read model for a route without a tip node. */
    public static RouteLineageView empty(UUID projectId, UUID routeId,
                                         UUID rootNodeId, String lifecycleStatus, boolean isActive) {
        return new RouteLineageView(projectId, routeId, rootNodeId, null,
                lifecycleStatus, isActive, List.of());
    }
}
