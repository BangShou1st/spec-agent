package com.specagent.graph;

import com.specagent.agent.AgentRunRepository;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteBranchType;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Graph workspace command integration tests: zero-model-call authoring,
 * free continuation with explicit branching, draft editing semantics, and
 * semantic relations — all append-preserving.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GraphCommandIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private AgentRunRepository agentRunRepository;

    private Project project;
    private Route initialRoute;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("图工作区命令测试");
        initialRoute = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    @Test
    void emptyProjectAcceptsRootDraftWithoutAnyModelRun() {
        Node node = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "先支持离线模式"));

        assertThat(node.kind()).isEqualTo(NodeKind.KNOWLEDGE);
        assertThat(node.authorKind()).isEqualTo(NodeAuthorKind.USER);
        assertThat(node.knowledgeStatus()).isEqualTo(KnowledgeStatus.PROPOSED);
        assertThat(node.isUserEditableDraft()).isTrue();

        Route route = routeRepository.findById(initialRoute.id()).orElseThrow();
        assertThat(route.rootNodeId()).isEqualTo(node.id());
        assertThat(route.tipNodeId()).isEqualTo(node.id());

        // Zero model calls: no agent run was created by graph commands.
        assertThat(agentRunRepository.findByProject(project.id())).isEmpty();

        assertThat(commandService.listOperations(project.id()))
                .anySatisfy(op -> {
                    assertThat(op.type()).isEqualTo(GraphOperation.Type.CREATE_DRAFT_NODE);
                    assertThat(op.actor()).isEqualTo(GraphOperation.Actor.USER);
                });
    }

    @Test
    void continuationAtTipAppendsWithoutBranch() {
        Node root = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "root"));

        GraphCommandService.ContinuationResult result = commandService.appendContinuation(
                project.id(), initialRoute.id(), root.id(), "REQUIREMENT",
                Map.of("text", "需求 A"));

        assertThat(result.branched()).isFalse();
        assertThat(result.node().parentNodeId()).isEqualTo(root.id());
        assertThat(routeRepository.findById(initialRoute.id()).orElseThrow().tipNodeId())
                .isEqualTo(result.node().id());
        assertThat(routeRepository.findByProject(project.id())).hasSize(1);
    }

    @Test
    void continuationFromHistoricalNodeCreatesBranchAndNeverRewritesHistory() {
        Node root = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "root"));
        Node tip = commandService.appendContinuation(
                project.id(), initialRoute.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip 内容")).node();

        // Continue from the historical root: must branch, not insert.
        GraphCommandService.ContinuationResult result = commandService.appendContinuation(
                project.id(), initialRoute.id(), root.id(), "IDEA", Map.of("text", "从根分叉"));

        assertThat(result.branched()).isTrue();
        Route branch = result.route();
        assertThat(branch.branchType()).isEqualTo(RouteBranchType.CONTINUATION);
        assertThat(branch.sourceRouteId()).isEqualTo(initialRoute.id());
        assertThat(branch.branchAtNodeId()).isEqualTo(root.id());
        assertThat(result.node().parentNodeId()).isEqualTo(root.id());

        // The original route's history is untouched: root->tip lineage intact.
        Route original = routeRepository.findById(initialRoute.id()).orElseThrow();
        assertThat(original.tipNodeId()).isEqualTo(tip.id());
        assertThat(original.rootNodeId()).isEqualTo(root.id());

        // The branch became the active route.
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(branch.id());
    }

    @Test
    void draftEditIsAllowedWhileProposedAndRejectedAfterwards() {
        Node draft = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "初稿"));

        Node revised = commandService.reviseDraftNode(
                project.id(), draft.id(), "REQUIREMENT", Map.of("text", "改成正式需求"));
        assertThat(revised.subtype()).isEqualTo("REQUIREMENT");
        assertThat(revised.contentText()).isEqualTo("改成正式需求");

        commandService.setKnowledgeStatus(project.id(), draft.id(), KnowledgeStatus.CONFIRMED);
        assertThatThrownBy(() -> commandService.reviseDraftNode(
                project.id(), draft.id(), "NOTE", Map.of("text", "改已确认内容")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an editable user draft");
    }

    @Test
    void semanticRelationRejectsDuplicatesAndSelfReference() {
        Node a = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), initialRoute.id(), a.id(), "NOTE", Map.of("text", "b")).node();

        NodeRelation relation = commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null);
        assertThat(relation.isActive()).isTrue();

        assertThatThrownBy(() -> commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        assertThatThrownBy(() -> commandService.createSemanticRelation(
                project.id(), a.id(), a.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    @Test
    void retractedNodesDisappearFromReadModelButKeepProvenance() {
        Node draft = commandService.createRootDraftNode(
                project.id(), initialRoute.id(), "NOTE", Map.of("text", "将撤销"));
        nodeRepository.updateRetracted(draft.id(), java.time.Instant.now());

        assertThat(nodeRepository.findById(draft.id())).isPresent();
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().isRetracted()).isTrue();
    }

    @Test
    void foreignNodeIsRejected() {
        Project other = projectService.createProject("另一个项目");
        Node foreign = commandService.createRootDraftNode(
                other.id(), routeRepository.findById(other.activeRouteId()).orElseThrow().id(),
                "NOTE", Map.of());

        assertThatThrownBy(() -> commandService.appendContinuation(
                project.id(), initialRoute.id(), foreign.id(), "NOTE", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to project");
    }
}
