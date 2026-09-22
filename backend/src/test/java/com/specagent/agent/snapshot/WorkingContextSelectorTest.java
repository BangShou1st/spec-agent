package com.specagent.agent.snapshot;

import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingContextSelectorTest {

    private final WorkingContextSelector selector = new WorkingContextSelector();

    @Test
    void derivedTailCannotDisplaceCurrentTipOrRecentRouteLineage() {
        UUID projectId = UUID.randomUUID();
        List<Node> routeLineage = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> node(projectId, "route-" + index, NodeKind.INTERACTION))
                .toList();
        List<Node> derived = java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> node(projectId, "derived-" + index, NodeKind.KNOWLEDGE))
                .toList();

        List<Node> selected = selector.select(routeLineage, derived,
                Set.of(routeLineage.get(routeLineage.size() - 1).id()),
                nodes -> nodes.size() * 100);

        assertThat(selected).hasSize(12);
        assertThat(selected).contains(routeLineage.get(routeLineage.size() - 1));
        assertThat(selected).containsExactlyElementsOf(routeLineage.subList(8, 20));
        assertThat(selected).noneMatch(node -> node.kind() == NodeKind.KNOWLEDGE);
    }

    @Test
    void selectionUsesModelFacingAnswerAndPatchBudget() {
        UUID projectId = UUID.randomUUID();
        List<Node> routeLineage = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> node(projectId, "route-" + index, NodeKind.INTERACTION))
                .toList();
        UUID tip = routeLineage.get(routeLineage.size() - 1).id();

        List<Node> selected = selector.select(routeLineage, List.of(), Set.of(tip), nodes -> {
            int chars = 2;
            for (Node node : nodes) {
                chars += node.id().equals(tip) ? 100 : 4_000;
            }
            return chars;
        });

        assertThat(selected).contains(tip == null ? null : routeLineage.get(routeLineage.size() - 1));
        assertThat(selected.size()).isLessThan(12);
        assertThat(selected.stream().mapToInt(node -> node.id().equals(tip) ? 100 : 4_000)
                .sum() + 2).isLessThanOrEqualTo(WorkingContextSelector.MAX_WORKING_LINEAGE_CHARS);
    }

    private Node node(UUID projectId, String text, NodeKind kind) {
        return new Node(UUID.randomUUID(), projectId, null, null, null,
                kind == NodeKind.INTERACTION ? text : null, null, List.of(), true,
                Instant.now(), kind, "TEST", Map.of("text", text),
                NodeAuthorKind.USER, null, null, Instant.now());
    }
}
