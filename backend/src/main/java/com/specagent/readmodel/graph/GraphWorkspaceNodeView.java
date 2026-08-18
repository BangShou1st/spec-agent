package com.specagent.readmodel.graph;

import com.specagent.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of one immutable node on the project graph.
 *
 * <p>Nodes are deduplicated across routes: shared nodes are rendered once and
 * route membership is supplied by each route's {@code lineageNodeIds}. Only
 * safe immutable node fields are exposed; answers, patches, context snapshots,
 * model payloads, provider data, and database internals are never exposed.
 */
public record GraphWorkspaceNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<GraphWorkspaceOptionView> options,
        boolean allowFreeAnswer,
        Instant createdAt) {

    public static GraphWorkspaceNodeView from(Node node) {
        return new GraphWorkspaceNodeView(
                node.id(), node.projectId(), node.parentNodeId(), node.supersedesNodeId(),
                node.question(), node.purpose(),
                node.options().stream().map(GraphWorkspaceOptionView::from).toList(),
                node.allowFreeAnswer(), node.createdAt());
    }
}
