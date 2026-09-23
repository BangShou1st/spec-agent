package com.specagent.workspace.route;

import java.util.UUID;

/**
 * Write-side port for route-related graph support: provenance validation and
 * the user-actor graph operation log entries that route lifecycle commands
 * must record.
 *
 * <p>The route domain needs the graph operation journal and the route
 * provenance invariant check, while the graph domain depends on route state —
 * this port inverts the route -&gt; graph write edge so the package pair stays
 * acyclic. Implemented by {@code com.specagent.workspace.graph.RouteGraphSupportAdapter}
 * as a thin delegation to the existing graph services.
 */
public interface RouteGraphSupportPort {

    /** Validates that a route's provenance chain is intact. */
    void validateRouteProvenance(UUID routeId);

    /**
     * Appends a user-actor graph operation entry for a route command.
     *
     * @param kind        the route operation kind (mapped to the graph journal type)
     * @param relatedIds  the route (and, for branch commands, produced node) ids
     * @param before      before-payload attributes (values may be strings or booleans)
     * @param after       after-payload attributes
     */
    void appendRouteOperation(UUID projectId, RouteOperationKind kind,
                              java.util.List<UUID> relatedIds,
                              java.util.Map<String, Object> before,
                              java.util.Map<String, Object> after);
}
