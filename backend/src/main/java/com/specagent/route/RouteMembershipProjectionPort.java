package com.specagent.route;

import java.util.Collection;
import java.util.UUID;

/**
 * Narrow write port for refreshing derived projections after route membership
 * changes. The route package owns the contract; retrieval provides the
 * rebuildable projection implementation.
 */
public interface RouteMembershipProjectionPort {

    /**
     * Refreshes node/resource provenance for the supplied route lineage roots
     * and their derived descendants. Implementations must not rebuild a whole
     * project or perform embedding-provider work.
     */
    void refreshRouteAffectedSources(UUID projectId,
                                     UUID routeId,
                                     Collection<UUID> lineageRootNodeIds);

    /** Refreshes exact Node/Resource sources after an attach/detach mutation. */
    void refreshNodeRouteProvenance(UUID projectId, Collection<UUID> nodeIds);
}
