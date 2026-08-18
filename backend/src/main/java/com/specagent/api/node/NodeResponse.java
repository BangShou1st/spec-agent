package com.specagent.api.node;

import com.specagent.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe node representation for the API boundary.
 *
 * <p>Exposes future-frontend-relevant immutable node data. Options carry
 * runtime-owned ids that are read-only. A node is never invented by the API;
 * an absent tip node is represented as {@code null} upstream.
 */
public record NodeResponse(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<NodeOptionResponse> options,
        boolean allowFreeAnswer,
        Instant createdAt) {

    public static NodeResponse from(Node node) {
        return new NodeResponse(
                node.id(),
                node.projectId(),
                node.parentNodeId(),
                node.supersedesNodeId(),
                node.question(),
                node.purpose(),
                node.options().stream().map(NodeOptionResponse::from).toList(),
                node.allowFreeAnswer(),
                node.createdAt());
    }
}