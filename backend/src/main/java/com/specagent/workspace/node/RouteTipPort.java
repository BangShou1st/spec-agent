package com.specagent.workspace.node;

import java.time.Instant;
import java.util.UUID;

/**
 * Port through which the node package advances the tip (and root) of the route
 * that owns a node.
 *
 * <p>Tip advancement is a route-owned invariant, but node creation is the
 * writer that triggers it. Declaring the seam here — instead of letting
 * {@link NodeService} depend on {@code route.RouteRepository} directly — keeps
 * the dependency one-way ({@code route -> node}, because {@code RouteService}
 * composes node creation) and therefore removes the {@code node <-> route}
 * package cycle. The implementation lives in the route package and delegates to
 * the existing repository statements unchanged.
 */
public interface RouteTipPort {

    /** The minimal route identity the tip-advance rule needs. */
    record RouteTip(UUID rootNodeId, UUID tipNodeId) {
    }

    /**
     * Reads the route's current root/tip pointers.
     *
     * @throws IllegalArgumentException when the route does not exist
     */
    RouteTip findTip(UUID routeId);

    /** Advances the route tip (and sets the root when it was still empty). */
    void advanceTipAndRoot(UUID routeId, UUID tipNodeId, UUID rootNodeId, Instant at);
}
