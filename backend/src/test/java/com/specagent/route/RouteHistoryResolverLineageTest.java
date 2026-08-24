package com.specagent.route;

import com.specagent.answer.AnswerRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteHistoryResolverLineageTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock
    private RouteRepository routeRepository;
    @Mock
    private NodeRepository nodeRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private RouteInheritedAnswerRepository inheritedAnswerRepository;
    @InjectMocks
    private RouteHistoryResolver resolver;

    @Test
    void cycleIsRejected() {
        UUID projectId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Map<UUID, Node> nodes = Map.of(
                firstId, node(firstId, projectId, secondId),
                secondId, node(secondId, projectId, firstId));
        stubNodes(nodes);

        assertThatThrownBy(() -> resolver.resolveLineage(firstId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Node lineage contains a cycle");
    }

    @Test
    void missingNodeIsRejected() {
        UUID missingId = UUID.randomUUID();
        when(nodeRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveLineage(missingId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Node not found: " + missingId);
    }

    @Test
    void depthBoundaryIsExplicit() {
        UUID projectId = UUID.randomUUID();
        Map<UUID, Node> nodes = new HashMap<>();
        UUID previous = null;
        UUID tenThousandTip = null;
        for (int i = 0; i < 10_000; i++) {
            UUID id = UUID.randomUUID();
            nodes.put(id, node(id, projectId, previous));
            previous = id;
            tenThousandTip = id;
        }
        stubNodes(nodes);

        assertThat(resolver.resolveLineage(tenThousandTip)).hasSize(10_000);

        UUID overflowTip = UUID.randomUUID();
        nodes.put(overflowTip, node(overflowTip, projectId, tenThousandTip));
        assertThatThrownBy(() -> resolver.resolveLineage(overflowTip))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Node lineage exceeds maximum depth");
    }

    private void stubNodes(Map<UUID, Node> nodes) {
        when(nodeRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        nodes.get(invocation.getArgument(0, UUID.class))));
    }

    private static Node node(UUID id, UUID projectId, UUID parentId) {
        return new Node(id, projectId, parentId, null, null,
                "Q", "P", List.of(), true, NOW);
    }
}
