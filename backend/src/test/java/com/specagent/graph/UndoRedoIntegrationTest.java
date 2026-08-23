package com.specagent.graph;

import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Undo/Redo as operation-specific compensation: preconditions are checked
 * before replay, immutable history is never deleted, and new work cuts off
 * the redo branch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UndoRedoIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private com.specagent.agent.policy.AgentProposalService proposalService;
    @Autowired private com.specagent.agent.policy.ProposalAcceptanceService acceptanceService;

    private Project project;
    private Route route;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("撤销重做测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    @Test
    void undoRootDraftClearsRouteAnchorAndRedoRestoresIt() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "根草稿"));

        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        UndoRedoService.UndoRedoResult undo = undoRedoService.undo(project.id());
        assertThat(undo.description()).contains("撤销");

        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();

        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        Route restored = routeRepository.findById(route.id()).orElseThrow();
        assertThat(restored.tipNodeId()).isEqualTo(root.id());
        assertThat(restored.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
    }

    @Test
    void undoAppendRollsBackTipToParent() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        undoRedoService.undo(project.id());

        Route rolled = routeRepository.findById(route.id()).orElseThrow();
        assertThat(rolled.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
    }

    @Test
    void undoCreateIsRejectedWhenDownstreamHistoryExists() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child"));

        // The latest operation (the continuation) is undoable; undo it, then
        // the root creation still has a live child? No — the child is now
        // retracted, but retraction is soft: the child row still references
        // the root, so undoing the root stays rejected until redo/cleanup.
        undoRedoService.undo(project.id());
        assertThatThrownBy(() -> undoRedoService.undo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("后续内容");
    }

    @Test
    void redoIsCutOffByNewWork() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        undoRedoService.undo(project.id());

        // New work after the undo cuts off the redo branch.
        commandService.createRootDraftNode(
                project.id(), route.id(), "IDEA", Map.of("text", "新内容"));

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法恢复");
    }

    @Test
    void undoDraftEditRestoresPriorContent() {
        Node draft = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "初稿"));
        commandService.reviseDraftNode(
                project.id(), draft.id(), "REQUIREMENT", Map.of("text", "第二稿"));

        undoRedoService.undo(project.id());

        Node restored = nodeRepository.findById(draft.id()).orElseThrow();
        assertThat(restored.contentText()).isEqualTo("初稿");
        assertThat(restored.subtype()).isEqualTo("NOTE");

        undoRedoService.redo(project.id());
        Node redone = nodeRepository.findById(draft.id()).orElseThrow();
        assertThat(redone.contentText()).isEqualTo("第二稿");
        assertThat(redone.subtype()).isEqualTo("REQUIREMENT");
    }

    @Test
    void undoBranchCreationSoftDeletesRouteAndRedoRestoresIt() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node tip = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "tip")).node();

        GraphCommandService.ContinuationResult branch = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "IDEA", Map.of("text", "branch"));
        assertThat(branch.branched()).isTrue();

        undoRedoService.undo(project.id()); // undoes the branch creation

        Route deleted = routeRepository.findById(branch.route().id()).orElseThrow();
        assertThat(deleted.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.DELETED);
        assertThat(nodeRepository.findById(branch.node().id()).orElseThrow().isRetracted()).isTrue();
        // Original route untouched.
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tip.id());

        undoRedoService.redo(project.id());
        Route restored = routeRepository.findById(branch.route().id()).orElseThrow();
        assertThat(restored.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(restored.tipNodeId()).isEqualTo(branch.node().id());
    }

    @Test
    void undoRelationRetractsAndRedoReactivates() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();

        undoRedoService.redo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * Strict-barrier contract: an accepted agent proposal is recorded as a
     * non-reversible ACTIVE operation and acts as an undo-history barrier.
     * Earlier reversible work becomes unreachable for undo — the service
     * never skips over the barrier to compensate an older operation, because
     * the accepted proposal's effects may depend on that older state.
     */
    @Test
    void acceptedAgentProposalIsAnUndoHistoryBarrier() {
        // Reversible user work first.
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        assertThat(undoRedoService.canUndo(project.id())).isTrue();

        // Then a non-reversible agent mutation via real proposal acceptance.
        com.specagent.agent.contract.ActionProposal proposal =
                new com.specagent.agent.contract.ActionProposal(
                        "CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                "content", Map.of("text", "agent 结论")),
                        java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
                        List.of(), java.util.UUID.randomUUID(), "idem-" + java.util.UUID.randomUUID(),
                        List.of());
        var pending = proposalService.createProposal(proposal,
                java.util.UUID.randomUUID(), project.id(), route.id());
        acceptanceService.acceptAndExecute(pending.id(), "user");

        // The ACCEPT_AGENT_PROPOSAL entry is ACTIVE and non-reversible.
        var acceptOperation = commandService.listOperations(project.id()).stream()
                .filter(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL)
                .findFirst().orElseThrow();
        assertThat(acceptOperation.reversible()).isFalse();
        assertThat(acceptOperation.status()).isEqualTo(GraphOperation.Status.ACTIVE);

        // Barrier semantics: the earlier reversible creation is NOT offered
        // as undoable, and a direct undo attempt is rejected fail-closed.
        assertThat(undoRedoService.canUndo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.undo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可撤销");
        // Nothing was undone by the rejected attempt.
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
        assertThat(undoRedoService.canRedo(project.id())).isFalse();

        // New reversible work on top of the barrier is undoable again — but
        // only down TO the barrier, never across it.
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        UndoRedoService.UndoRedoResult undo = undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        // After that undo the barrier blocks further undos again.
        assertThat(undoRedoService.canUndo(project.id())).isFalse();
    }
}
