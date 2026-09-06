package com.specagent.agent.snapshot;

import com.specagent.capability.CapabilityInvocationRecord;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Route-lineage visibility for capability observations.
 *
 * <p>Capability results are observations only: this policy decides which
 * stored results a route may see, and never creates nodes, claims,
 * decisions, or graph topology. Visibility follows the lineage that could
 * have produced the observation, not a plain route-id equality: results
 * anchored in a shared prefix stay visible to every inheriting branch,
 * while branch-private results stay on their own route.
 *
 * <p>Unattributable rows stay hidden (fail-closed): when neither the
 * invoking run nor any node reference ties a result to the current
 * lineage, it does not enter the agent input.
 */
public final class CapabilityObservationVisibility {

    private CapabilityObservationVisibility() {
    }

    /** Route and input-node attribution of the invoking run, if known. */
    public record RunAttribution(UUID routeId, UUID inputNodeId) {
    }

    /**
     * Every node the observation is provably about: node references in the
     * persisted result source refs plus node references anywhere inside the
     * invocation arguments. Malformed refs are ignored, never fatal.
     */
    public static Set<UUID> referencedNodeIds(CapabilityInvocationRecord record) {
        Set<UUID> refs = new LinkedHashSet<>();
        if (record.result() != null) {
            collectNodeRefs(record.result().get("sourceRefs"), refs);
        }
        collectNodeRefs(record.arguments(), refs);
        return Set.copyOf(refs);
    }

    private static void collectNodeRefs(Object value, Set<UUID> refs) {
        if (value instanceof String ref && ref.startsWith("node:")) {
            try {
                refs.add(UUID.fromString(ref.substring(5)));
            } catch (IllegalArgumentException expected) {
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                collectNodeRefs(entry, refs);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collectNodeRefs(entry, refs);
            }
        }
    }

    /**
     * True when the observation belongs to the given route context.
     * Own-route results are always visible; otherwise every referenced
     * node must sit on the current lineage. Reference-free results fall
     * back to the invoking run input node; rows with no attribution at
     * all stay hidden. The routeless-query case (null route id) only ever
     * matches through lineage membership, never through a null route
     * equality, so two floating anchors cannot see each other observations.
     */
    public static boolean isVisible(UUID snapshotRouteId,
                                      Set<UUID> lineageNodeIds,
                                      RunAttribution run,
                                      Set<UUID> referencedNodeIds) {
        if (snapshotRouteId != null && run != null && run.routeId() != null
                && run.routeId().equals(snapshotRouteId)) {
            return true;
        }
        if (!referencedNodeIds.isEmpty()) {
            return lineageNodeIds.containsAll(referencedNodeIds);
        }
        if (run != null && run.inputNodeId() != null) {
            return lineageNodeIds.contains(run.inputNodeId());
        }
        return false;
    }
}
