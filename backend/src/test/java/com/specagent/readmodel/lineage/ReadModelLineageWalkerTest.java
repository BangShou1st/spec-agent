package com.specagent.readmodel.lineage;

import com.specagent.node.Node;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadModelLineageWalkerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void cycleFailsClosedWithStableReason() {
        UUID projectId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Map<UUID, Node> nodes = new HashMap<>();
        nodes.put(firstId, node(firstId, projectId, secondId));
        nodes.put(secondId, node(secondId, projectId, firstId));

        assertThatThrownBy(() -> ReadModelLineageWalker.walk(firstId, id -> Optional.ofNullable(nodes.get(id))))
                .isInstanceOfSatisfying(ReadModelLineageWalker.LineageTraversalException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(ReadModelLineageWalker.Reason.CYCLE);
                    assertThat(ex).hasMessage("Route lineage contains a cycle");
                });
    }

    @Test
    void missingNodeFailsClosedWithStableReason() {
        UUID tipId = UUID.randomUUID();

        assertThatThrownBy(() -> ReadModelLineageWalker.walk(tipId, id -> Optional.empty()))
                .isInstanceOfSatisfying(ReadModelLineageWalker.LineageTraversalException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(ReadModelLineageWalker.Reason.MISSING_NODE);
                    assertThat(ex).hasMessage("A node in the route lineage does not resolve");
                });
    }

    @Test
    void canonicalDepthAllowsTenThousandNodesAndRejectsTheNextNode() {
        UUID projectId = UUID.randomUUID();
        Map<UUID, Node> nodes = new HashMap<>();
        UUID previous = null;
        UUID tipId = null;
        for (int i = 0; i < 10_001; i++) {
            UUID id = UUID.randomUUID();
            nodes.put(id, node(id, projectId, previous));
            previous = id;
            tipId = id;
        }

        UUID overflowTipId = tipId;
        assertThatThrownBy(() -> ReadModelLineageWalker.walk(overflowTipId,
                id -> Optional.ofNullable(nodes.get(id))))
                .isInstanceOfSatisfying(ReadModelLineageWalker.LineageTraversalException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(ReadModelLineageWalker.Reason.DEPTH_OVERFLOW);
                    assertThat(ex).hasMessage("Route lineage exceeds maximum depth");
                });

        assertThat(nodes).hasSize(10_001);
    }

    private static Node node(UUID id, UUID projectId, UUID parentId) {
        return new Node(id, projectId, parentId, null, null,
                "Q", "P", List.of(), true, NOW);
    }
}
