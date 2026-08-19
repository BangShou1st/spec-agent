package com.specagent.readmodel.graph;

import com.specagent.route.Route;

import java.util.List;
import java.util.UUID;

/**
 * Read-only route view on the project graph.
 *
 * <p>{@code lifecycleStatus} is the route lifecycle only
 * ({@code open|superseded|archived|deleted}); {@code isActive} is derived from
 * {@code Project.activeRouteId} at read time and never mutates route state.
 * {@code lineageNodeIds} is the authoritative root→tip node membership for this
 * route. Replacement metadata ({@code supersedesRouteId},
 * {@code replacementOfNodeId}) is exposed for display only and never injects
 * the superseded target into the lineage.
 */
public record GraphWorkspaceRouteView(
        UUID id,
        String label,
        String lifecycleStatus,
        boolean isActive,
        UUID rootNodeId,
        UUID tipNodeId,
        UUID createdFromNodeId,
        UUID supersedesRouteId,
        UUID replacementOfNodeId,
        String branchType,
        UUID sourceRouteId,
        UUID branchAtNodeId,
        List<UUID> lineageNodeIds) {

    public static GraphWorkspaceRouteView from(
            Route route, UUID activeRouteId, List<UUID> lineageNodeIds) {
        return new GraphWorkspaceRouteView(
                route.id(), route.label(), route.lifecycleStatus().code(),
                route.isActive(activeRouteId), route.rootNodeId(), route.tipNodeId(),
                route.createdFromNodeId(), route.supersedesRouteId(),
                route.replacementOfNodeId(),
                route.branchType() == null ? null : route.branchType().code(),
                route.sourceRouteId(), route.branchAtNodeId(), List.copyOf(lineageNodeIds));
    }
}
