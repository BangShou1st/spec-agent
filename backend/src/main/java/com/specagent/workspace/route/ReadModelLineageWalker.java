package com.specagent.workspace.route;

import com.specagent.workspace.node.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 文件名:ReadModelLineageWalker.java
 *
 * 用途:读模型共用的、fail-closed 的父链遍历器。本类只负责机械的
 * 链路遍历;归属校验和路线特有的根/尾节点语义仍由调用方在自己的 API
 * 边界处理。运行时上下文路径有自己权威的 {@code RouteHistoryResolver};
 * 这个辅助类的作用是避免两个展示用读模型各自实现遍历逻辑而逐渐不一致。
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
