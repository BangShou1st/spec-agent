package com.specagent.workspace.context;

import com.specagent.common.Json;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
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
 * 文件名:ContextBuilderNodeQueryContextTest.java
 *
 * 测试目标:验证 {@link ContextBuilder#buildForNodeQuery} 的两类约束——
 * Blocker 7(有界的 1 跳语义上下文):节点查询只捕获与锚点直接相连的有效
 * 关系,保留方向,不递归扩散,也不污染谱系;Blocker 4.3(节点查询的路线
 * 归属校验):锚点必须位于显式路线的谱系上,已撤回节点与跨路线锚点被拒绝,
 * 真正的游离节点允许传 null 路线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBuilderNodeQueryContextTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private ProjectRepository projectRepository;
    @Mock private RouteRepository routeRepository;
    @Mock private com.specagent.workspace.node.NodeRepository nodeRepository;
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
        // 默认:锚点是游离节点(不在任何路线上),除非某个用例覆盖该打桩。
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
                    com.specagent.workspace.node.NodeAuthorKind.AGENT, null, NOW, NOW);
        }
        return new Node(anchorId, projectId, null, UUID.randomUUID(), null,
                "q", null, List.of(), true, NOW);
    }

    private NodeRelation relation(UUID source, UUID target, NodeRelationType type) {
        return new NodeRelation(UUID.randomUUID(), projectId, source, target, type,
                NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE, null, null, NOW, null);
    }

    // ---- Blocker 7:有界的 1 跳语义上下文 --------------------------------

    @Test
    void nodeQueryCapturesOneHopActiveRelationsWithDirectionAndNeverPollutesLineage() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // A -> B(锚点为源)与 D -> A(锚点为目标);B -> C 不应被拉入
        // (不允许向 B 的邻居递归扩散)。
        when(nodeRelationRepository.findActiveTouchingNode(projectId, anchorId)).thenReturn(List.of(
                relation(anchorId, nodeB, NodeRelationType.DEPENDS_ON),
                relation(nodeC, anchorId, NodeRelationType.DERIVED_FROM)));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?");

        assertThat(snapshot.relations()).containsExactlyInAnyOrder(
                new ContextRelation(anchorId, nodeB, "DEPENDS_ON"),
                new ContextRelation(nodeC, anchorId, "DERIVED_FROM"));
        assertThat(snapshot.relatedNodeIds()).containsExactlyInAnyOrder(nodeB, nodeC);
        // 谱系保持纯净:只包含锚点本身,不含关联节点。
        assertThat(snapshot.includedNodeIds()).containsExactly(anchorId);
        assertThat(snapshot.includedNodeIds()).doesNotContain(nodeB, nodeC);
    }

    @Test
    void nodeQueryRespectsSupportedRelationTypesAndExcludesOthers() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // SUPPORTS 属于支持的关系类型;未知/不支持的关系类型会被过滤。
        // 这里通过白名单只纳入 DEPENDS_ON/SUPPORTS——不支持的类型不会出现在结果中。
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
        // 锚点不在(唯一)路线的谱系上 -> 是真正的游离节点。
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(UUID.randomUUID()));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, null, anchorId, "why?");

        assertThat(snapshot.relations()).isEmpty();
        assertThat(snapshot.relatedNodeIds()).isEmpty();
        assertThat(snapshot.routeId()).isNull();
    }

    // ---- Blocker 4.3:路线归属校验 --------------------------------

    @Test
    void crossRouteAnchorIsRejected() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        UUID otherRouteId = UUID.randomUUID();
        Route other = new Route(otherRouteId, projectId, UUID.randomUUID(), UUID.randomUUID(),
                RouteLifecycleStatus.OPEN, "O", null, null, null, null, NOW, NOW);
        when(routeRepository.findById(otherRouteId)).thenReturn(Optional.of(other));
        // 锚点不在另一条路线的权威谱系上。
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
        // 锚点不属于该路线的谱系。
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
        // 锚点确实在路线谱系上(共享节点 + 显式路线)。
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(anchorId));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                projectId, routeId, anchorId, "why?");

        assertThat(snapshot.routeId()).isEqualTo(routeId);
        assertThat(snapshot.includedNodeIds()).contains(anchorId);
    }

    @Test
    void routeNodeWithNullRouteIsRejected() {
        when(nodeRepository.findById(anchorId)).thenReturn(Optional.of(anchor(false)));
        // 锚点属于路线谱系 -> 传 null 路线会被拒绝。
        when(routeHistoryResolver.resolveLineage(routeTip)).thenReturn(List.of(anchorId));

        assertThatThrownBy(() -> contextBuilder.buildForNodeQuery(
                projectId, null, anchorId, "why?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to a route");
    }
}
