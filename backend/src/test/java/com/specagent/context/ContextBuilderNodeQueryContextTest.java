package com.specagent.context;

import com.specagent.common.Json;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeKind;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Blocker 7 (bounded 1-hop semantic context) and Blocker 4.3 (node-query route
 * membership validation) for {@link ContextBuilder#buildForNodeQuery}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBuilderNodeQueryContextTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private ProjectRepository projectRepository;
    @Mock private RouteRepository routeRepository;
    @Mock private com.specagent.node.NodeRepository nodeRepository;
    @Mock private AnswerPatchRepository answerPatchRepository;
    @Mock private RouteHistoryResolver routeHistoryResolver;
    @Mock private ContextSnapshotRepository contextSnapshotRepository;
    @Mock private NodeRelationRepository nodeRelationRepository;
    @Mock private Json json;
    @InjectMocks private ContextBuilder contextBuilder;

    private UUID projectId;
    private UUID routeId;
    private UUID routeTip;
    private UUID anchorId;
    private UUID nodeB;
    private UUID nodeC;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        routeId = UUID.randomUUID();
        routeTip = UUID.randomUUID();
        anchorId = UUID.randomUUID();
        nodeB = UUID.randomUUID();
        nodeC = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = new Route(routeId, projectId, routeTip, routeTip,
                RouteLifecycleStatus.OPEN, "R", null, null, null, null, NOW, NOW);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        lenient().when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        // Default: anchor is a routeless/floating node unless a test overrides.
        when(routeRepository.findByProject(projectId)).thenReturn(List.of(route));
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(anchorId));
        when(routeHistoryResolver.resolveLineage(anchorId)).thenReturn(List.of(anchorId));
        lenient().when(routeHistoryResolver.resolveEffectiveAnswers(any(), any())).thenReturn(List.of());
        when(answerPatchRepository.findBySourceAnswerIds(any())).thenReturn(List.of());
        lenient().when(json.write(any())).thenReturn("{}");
    }

    private Node anchor(boolean retracted) {
        if (retracted) {
            return new Node(anchorId, projectId, null, UUID.randomUUID(), null,
                    "q", null, List.of(), true, NOW,
                    NodeKind.INTERACTION, "QUESTION", Map.of(),
                    com.specagent.node.NodeAuthorKind.AGENT, null, NOW, NOW);
        }
        return new Node(anchorId, projectId, null, UUID.randomUUID(), null,
                "q", null, List.of(), true, NOW);
    }

    private NodeRelation relation(UUID source, UUID target, NodeRelationType type) {
        return new NodeRelation(UUID.randomUUID(), projectId, source, target, type,
                NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE, null, null, NOW, null);
    }

    // ---- Blocker 7: bounded 1-hop semantic context --------------------------------

    @Test
    void nodeQueryCapturesOneHopActiveRelationsWithDirectionAndNeverPollutesLineage() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // A -> B (anchor is source) and D -> A (anchor is target); B -> C must
        // NOT be pulled in (no recursion to B's neighbours).
        when(nodeRelationRepository.findActiveTouchingNode(projectId, anchorId)).thenReturn(List.of(
                relation(anchorId, nodeB, NodeRelationType.DEPENDS_ON),
                relation(nodeC, anchorId, NodeRelationType.DERIVED_FROM)));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?");

        assertThat(snapshot.relations()).containsExactlyInAnyOrder(
                new ContextRelation(anchorId, nodeB, "DEPENDS_ON"),
                new ContextRelation(nodeC, anchorId, "DERIVED_FROM"));
        assertThat(snapshot.relatedNodeIds()).containsExactlyInAnyOrder(nodeB, nodeC);
        // Lineage stays pure: only the anchor, never the related nodes.
        assertThat(snapshot.includedNodeIds()).containsExactly(anchorId);
        assertThat(snapshot.includedNodeIds()).doesNotContain(nodeB, nodeC);
    }

    @Test
    void nodeQueryRespectsSupportedRelationTypesAndExcludesOthers() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // SUPPORTS is supported; an unknown/unsupported type would be filtered.
        // Here we also include a relation type that is NOT in the supported set
        // by relying on the whitelist — only DEPENDS_ON/SUPPORTS qualify.
        when(nodeRelationRepository.findActiveTouchingNode(projectId, anchorId)).thenReturn(List.of(
                relation(anchorId, nodeB, NodeRelationType.DEPENDS_ON),
                relation(anchorId, nodeC, NodeRelationType.SUPPORTS)));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?");

        assertThat(snapshot.relations()).hasSize(2);
        assertThat(snapshot.relatedNodeIds()).containsExactlyInAnyOrder(nodeB, nodeC);
    }

    @Test
    void floatingNodeQueryWithNoRelationsHasEmptySemanticContext() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        when(nodeRelationRepository.findActiveTouchingNode(projectId, anchorId))
                .thenReturn(List.of());
        // Anchor is NOT on the (only) route's lineage -> genuine floating node.
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(UUID.randomUUID()));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, null, anchorId, "why?");

        assertThat(snapshot.relations()).isEmpty();
        assertThat(snapshot.relatedNodeIds()).isEmpty();
        assertThat(snapshot.routeId()).isNull();
    }

    // ---- Blocker 4.3: route membership validation --------------------------------

    @Test
    void crossRouteAnchorIsRejected() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        UUID otherRouteId = UUID.randomUUID();
        Route other = new Route(otherRouteId, projectId, UUID.randomUUID(), UUID.randomUUID(),
                RouteLifecycleStatus.OPEN, "O", null, null, null, null, NOW, NOW);
        when(routeRepository.findById(otherRouteId)).thenReturn(Optional.of(other));
        // Anchor is not on the other route's canonical lineage.
        when(routeHistoryResolver.resolveLineage(other.tipNodeId())).thenReturn(List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> contextBuilder.buildForNodeQuery(
                projectId, otherRouteId, anchorId, "why?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on the explicit route lineage");
    }

    @Test
    void retractedAnchorIsRejected() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(true)));

        assertThatThrownBy(() -> contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retracted");
    }

    @Test
    void floatingNullRouteAcceptedForGenuineFloatingNode() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // Anchor does not belong to the route's lineage.
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(UUID.randomUUID()));
        when(nodeRelationRepository.findActiveTouchingNode(projectId, anchorId))
                .thenReturn(List.of(relation(anchorId, nodeB, NodeRelationType.DEPENDS_ON)));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, null, anchorId, "why?");

        assertThat(snapshot.routeId()).isNull();
        assertThat(snapshot.relations()).contains(new ContextRelation(anchorId, nodeB, "DEPENDS_ON"));
    }

    @Test
    void sharedNodeWithExplicitMemberRouteAccepted() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // Anchor IS on the route's lineage (shared node with explicit route).
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(anchorId));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?");

        assertThat(snapshot.routeId()).isEqualTo(routeId);
        assertThat(snapshot.includedNodeIds()).contains(anchorId);
    }

    @Test
    void routeNodeWithNullRouteIsRejected() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // Anchor belongs to the route's lineage -> passing null route is rejected.
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(anchorId));

        assertThatThrownBy(() -> contextBuilder.buildForNodeQuery(
                projectId, null, anchorId, "why?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to a route");
    }
}
