package com.specagent.readmodel.graph;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the canonical graph workspace read model.
 *
 * <p>Covers deduplicated shared nodes, route-specific answers that stay
 * separate, inspection across every lifecycle state, replacement metadata that
 * never injects the superseded target into the replacement lineage, and
 * fail-closed behavior for missing/foreign/cyclic/root-mismatched lineage data.
 */
@ExtendWith(MockitoExtension.class)
class GraphWorkspaceQueryServiceTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private RouteService routeService;
    @Mock
    private NodeService nodeService;
    @Mock
    private AnswerService answerService;
    @InjectMocks
    private GraphWorkspaceQueryService service;

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    private static Node node(UUID id, UUID projectId, UUID parentNodeId, String question) {
        return new Node(id, projectId, parentNodeId, null, null,
                question, "P", List.of(), true, NOW);
    }

    private static Node nodeWithOption(UUID id, UUID projectId, UUID parentNodeId,
                                       String question, List<NodeOption> options) {
        return new Node(id, projectId, parentNodeId, null, null,
                question, "P", options, true, NOW);
    }

    private static Route route(UUID id, UUID projectId, UUID rootNodeId, UUID tipNodeId,
                               RouteLifecycleStatus status, UUID replacementOfNodeId) {
        return new Route(id, projectId, rootNodeId, tipNodeId, status, "R",
                null, null, replacementOfNodeId, null, NOW, NOW);
    }

    @Test
    void singleRouteReturnsCanonicalGraphView() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Node root = nodeWithOption(rootId, projectId, null, "Q1",
                List.of(NodeOption.of("A", "impact")));
        Node child = node(childId, projectId, rootId, "Q2");
        Route route = route(routeId, projectId, rootId, childId,
                RouteLifecycleStatus.OPEN, null);
        Answer answer = new Answer(UUID.randomUUID(), projectId, routeId, rootId,
                root.options().get(0).id().toString(), "answer", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));
        when(nodeService.getNode(rootId)).thenReturn(java.util.Optional.of(root));
        when(nodeService.getNode(childId)).thenReturn(java.util.Optional.of(child));
        when(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(rootId, childId)))
                .thenReturn(List.of(answer));

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.projectId()).isEqualTo(projectId);
        assertThat(view.activeRouteId()).isEqualTo(routeId);
        assertThat(view.routes()).singleElement()
                .satisfies(r -> assertThat(r.lineageNodeIds()).containsExactly(rootId, childId));
        assertThat(view.nodes()).extracting(GraphWorkspaceNodeView::id)
                .containsExactly(rootId, childId);
        assertThat(view.answers()).singleElement()
                .satisfies(a -> {
                    assertThat(a.routeId()).isEqualTo(routeId);
                    assertThat(a.nodeId()).isEqualTo(rootId);
                });
        verify(answerService).findAnswersForRouteAndNodeIds(routeId, List.of(rootId, childId));
    }

    @Test
    void sharedNodesAreDeduplicatedAndRouteSpecificAnswersStaySeparate() {
        UUID projectId = UUID.randomUUID();
        UUID routeAId = UUID.randomUUID();
        UUID routeBId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        UUID dId = UUID.randomUUID();

        Project project = new Project(projectId, "p", routeAId, null, NOW, NOW);
        Node a = node(aId, projectId, null, "A");
        Node b = node(bId, projectId, aId, "B");
        Node c = node(cId, projectId, bId, "C");
        Node d = node(dId, projectId, bId, "D");
        Route routeA = route(routeAId, projectId, aId, cId, RouteLifecycleStatus.OPEN, null);
        Route routeB = route(routeBId, projectId, aId, dId, RouteLifecycleStatus.OPEN, null);
        Answer answerBOnA = new Answer(UUID.randomUUID(), projectId, routeAId, bId,
                "opt", "B answer on A", "user", NOW);
        Answer answerBOnB = new Answer(UUID.randomUUID(), projectId, routeBId, bId,
                "opt", "B answer on B", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(routeA, routeB));
        when(nodeService.getNode(aId)).thenReturn(java.util.Optional.of(a));
        when(nodeService.getNode(bId)).thenReturn(java.util.Optional.of(b));
        when(nodeService.getNode(cId)).thenReturn(java.util.Optional.of(c));
        when(nodeService.getNode(dId)).thenReturn(java.util.Optional.of(d));
        when(answerService.findAnswersForRouteAndNodeIds(routeAId, List.of(aId, bId, cId)))
                .thenReturn(List.of(answerBOnA));
        when(answerService.findAnswersForRouteAndNodeIds(routeBId, List.of(aId, bId, dId)))
                .thenReturn(List.of(answerBOnB));

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.nodes()).extracting(GraphWorkspaceNodeView::id)
                .containsExactly(aId, bId, cId, dId);
        assertThat(view.routes()).hasSize(2);
        assertThat(view.routes().get(0).lineageNodeIds()).containsExactly(aId, bId, cId);
        assertThat(view.routes().get(1).lineageNodeIds()).containsExactly(aId, bId, dId);
        assertThat(view.answers()).hasSize(2);
        assertThat(view.answers()).filteredOn(ans -> ans.nodeId().equals(bId))
                .extracting(GraphWorkspaceAnswerView::routeId)
                .containsExactlyInAnyOrder(routeAId, routeBId);
    }

    @Test
    void allLifecycleStatesAreInspectableAndIsActiveFollowsActiveRouteIdOnly() {
        UUID projectId = UUID.randomUUID();
        UUID openId = UUID.randomUUID();
        UUID supersededId = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();

        Project project = new Project(projectId, "p", archivedId, null, NOW, NOW);
        Route open = route(openId, projectId, openId, openId, RouteLifecycleStatus.OPEN, null);
        Route superseded = route(supersededId, projectId, supersededId, supersededId,
                RouteLifecycleStatus.SUPERSEDED, null);
        Route archived = route(archivedId, projectId, archivedId, archivedId,
                RouteLifecycleStatus.ARCHIVED, null);
        Route deleted = route(deletedId, projectId, deletedId, deletedId,
                RouteLifecycleStatus.DELETED, null);
        Node openNode = node(openId, projectId, null, "Open question");
        Node supersededNode = node(supersededId, projectId, null, "Superseded question");
        Node archivedNode = node(archivedId, projectId, null, "Archived question");
        Node deletedNode = node(deletedId, projectId, null, "Deleted question");

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(
                List.of(open, superseded, archived, deleted));
        when(nodeService.getNode(openId)).thenReturn(java.util.Optional.of(openNode));
        when(nodeService.getNode(supersededId)).thenReturn(java.util.Optional.of(supersededNode));
        when(nodeService.getNode(archivedId)).thenReturn(java.util.Optional.of(archivedNode));
        when(nodeService.getNode(deletedId)).thenReturn(java.util.Optional.of(deletedNode));
        when(answerService.findAnswersForRouteAndNodeIds(openId, List.of(openId))).thenReturn(List.of());
        when(answerService.findAnswersForRouteAndNodeIds(supersededId, List.of(supersededId)))
                .thenReturn(List.of());
        when(answerService.findAnswersForRouteAndNodeIds(archivedId, List.of(archivedId)))
                .thenReturn(List.of());
        when(answerService.findAnswersForRouteAndNodeIds(deletedId, List.of(deletedId)))
                .thenReturn(List.of());

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.routes()).extracting(GraphWorkspaceRouteView::id)
                .containsExactly(openId, supersededId, archivedId, deletedId);
        assertThat(view.routes()).extracting(GraphWorkspaceRouteView::lifecycleStatus)
                .containsExactly("open", "superseded", "archived", "deleted");
        assertThat(view.routes()).filteredOn(r -> r.id().equals(archivedId))
                .singleElement().satisfies(r -> assertThat(r.isActive()).isTrue());
        assertThat(view.routes()).filteredOn(r -> !r.id().equals(archivedId))
                .allSatisfy(r -> assertThat(r.isActive()).isFalse());
    }

    @Test
    void routeWithoutTipAndRootYieldsEmptyLineage() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route empty = route(routeId, projectId, null, null, RouteLifecycleStatus.OPEN, null);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(empty));

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.routes()).singleElement()
                .satisfies(r -> {
                    assertThat(r.lineageNodeIds()).isEmpty();
                    assertThat(r.rootNodeId()).isNull();
                    assertThat(r.tipNodeId()).isNull();
                });
        assertThat(view.nodes()).isEmpty();
        assertThat(view.answers()).isEmpty();
    }

    @Test
    void replacementRouteLineageIsParentLineagePlusReplacementNodeOnly() {
        UUID projectId = UUID.randomUUID();
        UUID oldRouteId = UUID.randomUUID();
        UUID replacementRouteId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID grandchildId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();

        Project project = new Project(projectId, "p", replacementRouteId, null, NOW, NOW);
        Node root = node(rootId, projectId, null, "Root question");
        Node child = node(childId, projectId, rootId, "Child question");
        Node grandchild = node(grandchildId, projectId, childId, "Grandchild question");
        Node replacement = new Node(replacementId, projectId, rootId, null, childId,
                "Replacement question", "Replacement purpose", List.of(), true, NOW);
        Route oldRoute = route(oldRouteId, projectId, rootId, grandchildId,
                RouteLifecycleStatus.SUPERSEDED, null);
        Route replacementRoute = route(replacementRouteId, projectId, rootId, replacementId,
                RouteLifecycleStatus.OPEN, childId);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(oldRoute, replacementRoute));
        when(nodeService.getNode(rootId)).thenReturn(java.util.Optional.of(root));
        when(nodeService.getNode(childId)).thenReturn(java.util.Optional.of(child));
        when(nodeService.getNode(grandchildId)).thenReturn(java.util.Optional.of(grandchild));
        when(nodeService.getNode(replacementId)).thenReturn(java.util.Optional.of(replacement));
        when(answerService.findAnswersForRouteAndNodeIds(oldRouteId,
                List.of(rootId, childId, grandchildId))).thenReturn(List.of());
        when(answerService.findAnswersForRouteAndNodeIds(replacementRouteId,
                List.of(rootId, replacementId))).thenReturn(List.of());

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.routes()).extracting(GraphWorkspaceRouteView::id)
                .containsExactly(oldRouteId, replacementRouteId);
        GraphWorkspaceRouteView replacementView = view.routes().get(1);
        // Parent lineage plus the replacement node only; the superseded target
        // and its old child subtree never enter the replacement lineage.
        assertThat(replacementView.lineageNodeIds()).containsExactly(rootId, replacementId);
        assertThat(replacementView.replacementOfNodeId()).isEqualTo(childId);
        assertThat(view.routes().get(0).lineageNodeIds())
                .containsExactly(rootId, childId, grandchildId);
        // SupersedesNodeId is metadata only, exposed on the node view.
        assertThat(view.nodes()).filteredOn(n -> n.id().equals(replacementId))
                .singleElement()
                .satisfies(n -> assertThat(n.supersedesNodeId()).isEqualTo(childId));
        assertThat(view.nodes()).extracting(GraphWorkspaceNodeView::id)
                .containsExactly(rootId, childId, grandchildId, replacementId);
    }

    @Test
    void missingProjectFailsWithProjectNotFound() {
        UUID projectId = UUID.randomUUID();
        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.PROJECT_NOT_FOUND));
    }

    @Test
    void tipReferencingMissingNodeFailsClosed() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = route(routeId, projectId, missingId, missingId,
                RouteLifecycleStatus.OPEN, null);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));
        when(nodeService.getNode(missingId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION));
    }

    @Test
    void nodeFromAnotherProjectFailsClosed() {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = route(routeId, projectId, nodeId, nodeId, RouteLifecycleStatus.OPEN, null);
        Node foreign = node(nodeId, otherProjectId, null, "FOREIGN_SENTINEL");

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));
        when(nodeService.getNode(nodeId)).thenReturn(java.util.Optional.of(foreign));

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION));
    }

    @Test
    void cyclicLineageFailsClosed() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID selfId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = route(routeId, projectId, selfId, selfId, RouteLifecycleStatus.OPEN, null);
        Node self = new Node(selfId, projectId, selfId, null, null,
                "Self question", null, List.of(), true, NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));
        when(nodeService.getNode(selfId)).thenReturn(java.util.Optional.of(self));

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION));
    }

    @Test
    void rootMismatchFailsClosed() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID rootAId = UUID.randomUUID();
        UUID childAId = UUID.randomUUID();
        UUID rootBId = UUID.randomUUID();
        UUID childBId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        // Route root stays rootA but the tip walks a rootB lineage.
        Route route = route(routeId, projectId, rootAId, childBId, RouteLifecycleStatus.OPEN, null);
        Node rootA = node(rootAId, projectId, null, "Root A");
        Node childA = node(childAId, projectId, rootAId, "Child of A");
        Node rootB = node(rootBId, projectId, null, "Root B");
        Node childB = node(childBId, projectId, rootBId, "Child of B");

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));
        when(nodeService.getNode(childBId)).thenReturn(java.util.Optional.of(childB));
        when(nodeService.getNode(rootBId)).thenReturn(java.util.Optional.of(rootB));

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION));
    }

    @Test
    void nullTipWithNonNullRootFailsClosed() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = route(routeId, projectId, rootId, null, RouteLifecycleStatus.OPEN, null);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(route));

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION));
    }
}
