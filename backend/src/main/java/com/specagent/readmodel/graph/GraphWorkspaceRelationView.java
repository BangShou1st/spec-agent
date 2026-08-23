package com.specagent.readmodel.graph;

import com.specagent.graph.NodeRelation;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of an active semantic relation.
 *
 * <p>Semantic relations are reasoning metadata shown in the Inspector or a
 * selectable relation layer; they are never projected as default Canvas
 * continuation edges.
 */
public record GraphWorkspaceRelationView(
        UUID id,
        UUID sourceNodeId,
        UUID targetNodeId,
        String relationType,
        String origin,
        UUID createdByProposalId,
        Instant createdAt) {

    public static GraphWorkspaceRelationView from(NodeRelation relation) {
        return new GraphWorkspaceRelationView(
                relation.id(), relation.sourceNodeId(), relation.targetNodeId(),
                relation.relationType().code(), relation.origin().name(),
                relation.createdByProposalId(), relation.createdAt());
    }
}
