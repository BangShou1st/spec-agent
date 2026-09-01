package com.specagent.agent.contract;

import java.util.UUID;

/**
 * One direction-preserving semantic relation on the wire. Mirrors the durable
 * {@code ContextRelation}: source, target and the relation type code. The
 * decision engine reads direction from the stored endpoints.
 */
public record RelationView(UUID sourceNodeId, UUID targetNodeId, String relationType) {
}
