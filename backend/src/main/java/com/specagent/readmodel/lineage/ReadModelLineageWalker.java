package com.specagent.readmodel.lineage;

import com.specagent.node.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared fail-closed parent-chain traversal for read models.
 *
 * <p>The walker owns only the mechanical chain traversal. Callers retain
 * ownership checks and route-specific root/tip semantics at their API boundary.
 * The runtime context path has its own authoritative {@code RouteHistoryResolver};
 * this helper keeps the two display read models from drifting apart.
 */
public final class ReadModelLineageWalker {

    private static final int MAX_LINEAGE_DEPTH = 10_000;

    private ReadModelLineageWalker() {
    }

    public static List<Node> walk(UUID tipNodeId,
                                   Function<UUID, Optional<Node>> nodeLoader) {
        List<Node> fromTipToRoot = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        UUID current = tipNodeId;

        while (current != null) {
            if (!visited.add(current)) {
                throw new LineageTraversalException(Reason.CYCLE,
                        "Route lineage contains a cycle");
            }
            if (fromTipToRoot.size() >= MAX_LINEAGE_DEPTH) {
                throw new LineageTraversalException(Reason.DEPTH_OVERFLOW,
                        "Route lineage exceeds maximum depth");
            }
            Node node = nodeLoader.apply(current)
                    .orElseThrow(() -> new LineageTraversalException(Reason.MISSING_NODE,
                            "A node in the route lineage does not resolve"));
            fromTipToRoot.add(node);
            current = node.parentNodeId();
        }

        Collections.reverse(fromTipToRoot);
        return List.copyOf(fromTipToRoot);
    }

    public enum Reason {
        CYCLE,
        MISSING_NODE,
        DEPTH_OVERFLOW
    }

    public static final class LineageTraversalException extends RuntimeException {
        private final Reason reason;

        public LineageTraversalException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
