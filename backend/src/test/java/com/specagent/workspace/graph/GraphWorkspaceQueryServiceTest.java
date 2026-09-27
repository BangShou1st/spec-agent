package com.specagent.workspace.graph;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件名:GraphWorkspaceQueryServiceTest.java
 *
 * 测试目标:图工作区权威读取模型的单元测试。覆盖:共享节点去重、各路线
 * 的回答按路线区分、全部生命周期状态可查看、替换元数据不会把被取代目标
 * 注入替换谱系,以及缺失/跨项目/成环/根不匹配等脏谱系数据的快速失败行为。
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
    @Mock
    private NodeRelationRepository relationRepository;
    @InjectMocks
    private GraphWorkspaceQueryService service;

    @BeforeEach
    void stubRelations() {
        lenient().when(relationRepository.findActiveByProject(any()))
                .thenReturn(List.of());
    }

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
    void sharedNodesAreDeduplicatedAndSingleAnswerIdentityStaysShared() {
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
        // 一个权威 Question 只携带一个不可变 Answer 身份;两条路线把共享节点
        // 解析到同一个 answer id(路线 B 通过继承引用),因此读取模型暴露的
        // 按路线视图在内容上绝不产生分歧。
        UUID sharedAnswerId = UUID.randomUUID();
        Answer answerB = new Answer(sharedAnswerId, projectId, routeAId, bId,
                "opt", "B answer", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(routeA, routeB));
        when(nodeService.getNode(aId)).thenReturn(java.util.Optional.of(a));
        when(nodeService.getNode(bId)).thenReturn(java.util.Optional.of(b));
        when(nodeService.getNode(cId)).thenReturn(java.util.Optional.of(c));
        when(nodeService.getNode(dId)).thenReturn(java.util.Optional.of(d));
        when(answerService.findAnswersForRouteAndNodeIds(routeAId, List.of(aId, bId, cId)))
                .thenReturn(List.of(answerB));
        when(answerService.findAnswersForRouteAndNodeIds(routeBId, List.of(aId, bId, dId)))
                .thenReturn(List.of(answerB));

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.nodes()).extracting(GraphWorkspaceNodeView::id)
                .containsExactly(aId, bId, cId, dId);
        assertThat(view.routes()).hasSize(2);
        assertThat(view.routes().get(0).lineageNodeIds()).containsExactly(aId, bId, cId);
        assertThat(view.routes().get(1).lineageNodeIds()).containsExactly(aId, bId, dId);
        // 存在按路线的回答视图(属主 + 继承),均携带同一个 Answer 身份,
        // 一个节点绝不出现两个相互竞争的回答。
        assertThat(view.answers()).hasSize(2);
        assertThat(view.answers()).extracting(GraphWorkspaceAnswerView::id)
                .containsOnly(sharedAnswerId);
        assertThat(view.answers()).filteredOn(ans -> ans.nodeId().equals(bId))
                .extracting(GraphWorkspaceAnswerView::routeId)
                .containsExactlyInAnyOrder(routeAId, routeBId);
    }

    @Test
    void sharedQuestionAnsweredWithSameIdentityAcrossRoutesSucceeds() {
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
        // 共享节点在两条路线上携带同一个有效 Answer 身份(路线 B 通过继承
        // 引用);读取模型不得将其判定为分歧。
        UUID sharedAnswerId = UUID.randomUUID();
        Answer answer = new Answer(sharedAnswerId, projectId, routeAId, bId,
                "opt", "B answer", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(routeA, routeB));
        when(nodeService.getNode(aId)).thenReturn(java.util.Optional.of(a));
        when(nodeService.getNode(bId)).thenReturn(java.util.Optional.of(b));
        when(nodeService.getNode(cId)).thenReturn(java.util.Optional.of(c));
        when(nodeService.getNode(dId)).thenReturn(java.util.Optional.of(d));
        when(answerService.findAnswersForRouteAndNodeIds(routeAId, List.of(aId, bId, cId)))
                .thenReturn(List.of(answer));
        when(answerService.findAnswersForRouteAndNodeIds(routeBId, List.of(aId, bId, dId)))
                .thenReturn(List.of(answer));

        GraphWorkspaceView view = service.getForProject(projectId);

        assertThat(view.answers()).hasSize(2);
        assertThat(view.answers()).extracting(GraphWorkspaceAnswerView::id)
                .containsOnly(sharedAnswerId);
    }

    @Test
    void sharedQuestionAnsweredInOneRouteAndUnansweredInAnotherFailsClosed() {
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
        // 共享的权威 Question 节点 b 在路线 A 上已回答、在路线 B 上未回答
        // (该路线上没有解析出有效回答)——这是"已答/未答"分歧,
        // 不是正常的 UI 状态。
        Answer answerB = new Answer(UUID.randomUUID(), projectId, routeAId, bId,
                "opt", "B answer", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(routeA, routeB));
        when(nodeService.getNode(aId)).thenReturn(java.util.Optional.of(a));
        when(nodeService.getNode(bId)).thenReturn(java.util.Optional.of(b));
        when(nodeService.getNode(cId)).thenReturn(java.util.Optional.of(c));
        when(nodeService.getNode(dId)).thenReturn(java.util.Optional.of(d));
        when(answerService.findAnswersForRouteAndNodeIds(routeAId, List.of(aId, bId, cId)))
                .thenReturn(List.of(answerB));
        when(answerService.findAnswersForRouteAndNodeIds(routeBId, List.of(aId, bId, dId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOfSatisfying(GraphWorkspaceQueryException.class, e -> {
                    assertThat(e.reason())
                            .isEqualTo(GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION);
                    assertThat(e.getMessage()).contains("SHARED_STATE_DIVERGENCE");
                });
    }

    @Test
    void distinctAnswerIdsOnSameCanonicalNodeFailClosedAsDivergence() {
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
        Answer answer1 = new Answer(UUID.randomUUID(), projectId, routeAId, bId,
                "opt", "first answer", "user", NOW);
        Answer answer2 = new Answer(UUID.randomUUID(), projectId, routeBId, bId,
                "opt", "second answer", "user", NOW);

        when(projectService.getProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(routeService.listRoutes(projectId)).thenReturn(List.of(routeA, routeB));
        when(nodeService.getNode(aId)).thenReturn(java.util.Optional.of(a));
        when(nodeService.getNode(bId)).thenReturn(java.util.Optional.of(b));
        when(nodeService.getNode(cId)).thenReturn(java.util.Optional.of(c));
        when(nodeService.getNode(dId)).thenReturn(java.util.Optional.of(d));
        when(answerService.findAnswersForRouteAndNodeIds(routeAId, List.of(aId, bId, cId)))
                .thenReturn(List.of(answer1));
        when(answerService.findAnswersForRouteAndNodeIds(routeBId, List.of(aId, bId, dId)))
                .thenReturn(List.of(answer2));

        assertThatThrownBy(() -> service.getForProject(projectId))
                .isInstanceOf(GraphWorkspaceQueryException.class)
                .hasMessageContaining("SHARED_STATE_DIVERGENCE");
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
        // 替换谱系 = 父谱系 + 替换节点本身;被取代目标及其旧子树
        // 绝不进入替换谱系。
        assertThat(replacementView.lineageNodeIds()).containsExactly(rootId, replacementId);
        assertThat(replacementView.replacementOfNodeId()).isEqualTo(childId);
        assertThat(view.routes().get(0).lineageNodeIds())
                .containsExactly(rootId, childId, grandchildId);
        // SupersedesNodeId 只是元数据,在节点视图上暴露。
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
        // 路线根仍是 rootA,但 tip 沿 rootB 的谱系行走。
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
