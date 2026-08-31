package com.specagent.graph;

import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
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
import java.util.UUID;

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
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private com.specagent.agent.policy.AgentProposalService proposalService;
    @Autowired private com.specagent.agent.policy.ProposalAcceptanceService acceptanceService;
    @Autowired private com.specagent.route.RouteService routeService;
    @Autowired private com.specagent.project.ProjectRepository projectRepository;

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
    void floatingDraftNeverTouchesRouteAnchorAndUndoRedoKeepsItDisconnected() {
        // The route already has content; a floating idea must not rewire it.
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node floating = commandService.createFloatingDraftNode(
                project.id(), route.id(), "IDEA", Map.of("text", "灵感"));

        Route afterCreate = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterCreate.tipNodeId()).isEqualTo(root.id());
        assertThat(floating.parentNodeId()).isNull();
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();

        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        Route afterUndo = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterUndo.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isTrue();

        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        Route afterRedo = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterRedo.tipNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();
    }

    /**
     * Blocked regressions: a floating draft created with NO route at all
     * (project active route archived/removed, routeId=null) must undo and
     * redo without ever consulting a route — replayNodeCreation previously
     * resolved the route before the isFloatingCreation branch, so redo failed
     * at the missing-route lookup.
     */
    @Test
    void floatingNoRouteCreationUndoRedoWorksWithoutAnyRoute() {
        // 1. Project with an active route, then remove that route (archived)
        //    so the project has NO active route at all.
        UUID firstActiveRouteId = project.activeRouteId();
        routeService.archiveRoute(project.id(), firstActiveRouteId);
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        // 2. Create a floating node with routeId=null (no creation context
        //    route either).
        Node floating = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "无路线灵感"));

        // 4. The node is fully disconnected and the project still has no
        //    active route.
        assertThat(floating.parentNodeId()).isNull();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        Route archived = routeRepository.findById(firstActiveRouteId).orElseThrow();
        UUID archivedTip = archived.tipNodeId();
        UUID archivedRoot = archived.rootNodeId();

        // 5. Undo: the floating node is retracted; active stays null; the
        //    archived route is untouched.
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isTrue();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().tipNodeId())
                .isEqualTo(archivedTip);
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().rootNodeId())
                .isEqualTo(archivedRoot);

        // 6-10. Redo: the node is restored; active stays null; the archived
        //    route is still untouched.
        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(floating.id()).orElseThrow().isRetracted()).isFalse();
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().tipNodeId())
                .isEqualTo(archivedTip);
        assertThat(routeRepository.findById(firstActiveRouteId).orElseThrow().rootNodeId())
                .isEqualTo(archivedRoot);
    }

    /**
     * Fix for the second-undo permanent failure: a child that was itself undone
     * (soft-retracted) must NOT block undoing its parent. Previously the
     * existence check ignored {@code retracted_at} and the parent creation could
     * never be undone once any descendant existed.
     */
    @Test
    void undoRootSucceedsAfterChildUndoBecauseRetractedChildIsNotLiveDownstream() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        // Undo the child (latest op). It is soft-retracted, not deleted.
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();

        // The retracted child no longer counts as live downstream history, so
        // the parent creation is now undoable.
        assertThat(undoRedoService.canUndo(project.id())).isTrue();
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();
        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();
    }

    /**
     * B2.2 end-to-end: create root; append child; undo child; undo root; redo
     * root; redo child — every step succeeds and the route root/tip is correctly
     * restored from the (now retracted) child's undo.
     */
    @Test
    void undoChildThenRootThenRedoRootThenChildRestoresRoute() {
        Node root = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), route.id(), root.id(), "NOTE", Map.of("text", "child")).node();

        undoRedoService.undo(project.id()); // undo child (leaf)
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isTrue();
        undoRedoService.undo(project.id()); // undo root — must not be blocked
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();

        Route cleared = routeRepository.findById(route.id()).orElseThrow();
        assertThat(cleared.tipNodeId()).isNull();
        assertThat(cleared.rootNodeId()).isNull();

        // Redo replays the most-recently-undone (root) first, then child.
        undoRedoService.redo(project.id());
        Route afterRoot = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterRoot.tipNodeId()).isEqualTo(root.id());
        assertThat(afterRoot.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();

        undoRedoService.redo(project.id());
        Route afterChild = routeRepository.findById(route.id()).orElseThrow();
        assertThat(afterChild.tipNodeId()).isEqualTo(child.id());
        assertThat(afterChild.rootNodeId()).isEqualTo(root.id());
        assertThat(nodeRepository.findById(child.id()).orElseThrow().isRetracted()).isFalse();
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

    /**
     * B2.1: redo replays the most-recently-undone operation first. Multiple
     * undos then redos must rebuild the original operation order by {@code
     * undoneAt} (not {@code createdAt}): the last thing undone is the first thing
     * redone, so a sequence of edits is restored in its authored order.
     */
    @Test
    void redoReplaysMostRecentlyUndoneOperationFirst() {
        Node draft = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "初稿"));
        commandService.reviseDraftNode(project.id(), draft.id(), "REQUIREMENT", Map.of("text", "第二稿"));
        commandService.reviseDraftNode(project.id(), draft.id(), "RISK", Map.of("text", "第三稿"));

        // Undo the three edits (latest-first each time).
        undoRedoService.undo(project.id());
        undoRedoService.undo(project.id());
        undoRedoService.undo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("初稿");

        // Redo replays in reverse-undo order: 初稿, then 第二稿, then 第三稿.
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("初稿");
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("第二稿");
        undoRedoService.redo(project.id());
        assertThat(nodeRepository.findById(draft.id()).orElseThrow().contentText()).isEqualTo("第三稿");
    }

    /**
     * B2.4-1: a relation undo retracts it and redo re-activates it through the
     * invariant boundary (this is also covered by the existing
     * undoRelationRetractsAndRedoReactivates, kept here for the B2.4 matrix).
     */
    @Test
    void redoRelationReactivatesThroughInvariantBoundary() {
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
     * B2.4-2: after undoing a relation, intervening work creates the same
     * relation again (now active). Redo must be rejected (cut off by new work,
     * and the duplicate backstop in replay would also fire). The original
     * relation stays retracted.
     */
    @Test
    void redoRelationRejectedWhenConflictingActiveRelationExists() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());

        // Intervening work re-creates the identical relation (now active).
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
        assertThat(relationRepository.findActiveByProject(project.id()).get(0).relationType())
                .isEqualTo(NodeRelationType.SUPPORTS);
    }

    /**
     * B2.4-3: undo a DEPENDS_ON, then intervening work creates B DEPENDS_ON A.
     * Replaying A DEPENDS_ON B would close the cycle, so redo is rejected and the
     * original relation stays retracted.
     */
    @Test
    void redoDependsOnRejectedWhenItWouldCreateCycle() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id()); // undo A DEPENDS_ON B

        // Intervening work: B DEPENDS_ON A. Replaying A DEPENDS_ON B now forms a
        // cycle (A -> B -> A).
        commandService.createSemanticRelation(
                project.id(), b.id(), a.id(), NodeRelationType.DEPENDS_ON,
                NodeRelation.Origin.USER, null, null);

        assertThat(undoRedoService.canRedo(project.id())).isFalse();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class);
        // Only B -> A is active; A -> B remains retracted.
        assertThat(relationRepository.findActiveByProject(project.id())).hasSize(1);
    }

    /**
     * B2.4-4: an endpoint is retracted after the relation is undone. Redo must
     * re-validate through the invariant boundary and be rejected because a
     * relation cannot reference a retracted node.
     */
    @Test
    void redoRelationRejectedWhenEndpointRetracted() {
        Node a = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "a"));
        Node b = commandService.appendContinuation(
                project.id(), route.id(), a.id(), "NOTE", Map.of("text", "b")).node();
        commandService.createSemanticRelation(
                project.id(), a.id(), b.id(), NodeRelationType.SUPPORTS,
                NodeRelation.Origin.USER, null, null);

        undoRedoService.undo(project.id());
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();

        // The endpoint B is retracted out-of-band (e.g. by another graph action
        // orthogonal to this operation stack).
        nodeService.setRetracted(b.id(), true);

        // Cut-off does not fire (no new GraphOperation), but the invariant
        // boundary rejects the replay.
        assertThat(undoRedoService.canRedo(project.id())).isTrue();
        assertThatThrownBy(() -> undoRedoService.redo(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETRACTED_NODE_REFERENCE");
        assertThat(relationRepository.findActiveByProject(project.id())).isEmpty();
    }
}
