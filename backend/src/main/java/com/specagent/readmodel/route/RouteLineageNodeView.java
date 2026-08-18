package com.specagent.readmodel.route;

import com.specagent.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of one immutable node on a route lineage.
 *
 * <p>Exposes only the safe immutable node fields the UI needs to identify and
 * inspect a historical clarification node before fork/regenerate. Answers,
 * patches, context snapshots, model payloads, provider data, and database
 * internals are never exposed.
 */
public record RouteLineageNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<RouteLineageOptionView> options,
        boolean allowFreeAnswer,
        Instant createdAt) {

    public static RouteLineageNodeView from(Node node) {
        return new RouteLineageNodeView(
                node.id(),
                node.projectId(),
                node.parentNodeId(),
                node.supersedesNodeId(),
                node.question(),
                node.purpose(),
                node.options().stream().map(RouteLineageOptionView::from).toList(),
                node.allowFreeAnswer(),
                node.createdAt());
    }
}
