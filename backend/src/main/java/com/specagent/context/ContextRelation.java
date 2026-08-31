package com.specagent.context;

import java.util.UUID;

/**
 * One direction-preserving ACTIVE semantic relation in a frozen context.
 *
 * <p>Used by the bounded 1-hop semantic context for a node query: the relation
 * is stored exactly as persisted (source/target/type) so the decision engine
 * can see direction. Symmetric relation types are normalized at write time by
 * {@code GraphInvariantValidator.endpointsCanonicalized}, so the stored
 * direction for RELATED_TO / CONFLICTS_WITH may be {@code (minId, maxId)}; this
 * record keeps that stored direction untouched.
 */
public record ContextRelation(UUID sourceNodeId, UUID targetNodeId, String relationType) {

    public ContextRelation {
        if (sourceNodeId == null || targetNodeId == null) {
            throw new IllegalArgumentException("A context relation must have both endpoints");
        }
        if (relationType == null || relationType.isBlank()) {
            throw new IllegalArgumentException("A context relation must have a relation type");
        }
    }
}
