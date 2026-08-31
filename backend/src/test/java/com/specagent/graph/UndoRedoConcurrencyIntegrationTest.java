package com.specagent.graph;

import com.specagent.agent.action.StaleProposalException;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalAcceptanceService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Undo/Redo concurrency: two real independent transactions racing on the same
 * project/operation stack must serialize against each other and against live
 * graph mutations, so the linear undo/redo stack is never half-applied or
 * double-applied.
 *
 * <p>Deliberately NOT {@code @Transactional}: each racer must run in its own
 * database transaction (the service methods are transactional and acquire the
 * relevant row locks), so the setup rows are committed before the threads
 * start and the locks actually contend.
 */
@SpringBootTest
@ActiveProfiles("test")
class UndoRedoConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private AnswerService answerService;
    @Autowired private NodeService nodeService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteService routeService;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private GraphOperationRepository operationRepository;
    @Autowired private AgentProposalService proposalService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;

    @AfterEach
    void cleanUp() {
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM agent_run_events "
                + "WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers "
                + "WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * B2.3: Answer finalization and Undo race on the same node. The node-row
     * lock serializes them, so exactly one wins — and the forbidden state "a
     * retracted node carrying an immutable Answer" can never be observed. If the
     * Answer wins, Undo fails closed (node stays active); if Undo wins, the
     * later Answer fails closed (RETRACTED_NODE_REFERENCE).
     */
    @Test
    void undoAndAnswerFinalizationNeverCoexistRetractedNodeWithImmutableAnswer() throws Exception {
        project = projectService.createProject("撤销与回答并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> answerFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), routeId, root.id(), null, "回答A", "user"));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt answerAttempt = answerFuture.get(60, TimeUnit.SECONDS);

            // Exactly one of the two conflicting operations succeeds.
            assertThat(undoAttempt.success ^ answerAttempt.success)
                    .as("exactly one of undo / finalize may succeed")
                    .isTrue();

            boolean nodeRetracted = nodeRepository.findById(root.id()).orElseThrow().isRetracted();
            boolean answerExists = answerService.existsAnswerFor(routeId, root.id());

            // The forbidden coexistence is never observed.
            assertThat(nodeRetracted && answerExists)
                    .as("a retracted node must never carry an immutable answer")
                    .isFalse();

            if (undoAttempt.success) {
                assertThat(nodeRetracted).isTrue();
                assertThat(answerExists).isFalse();
                assertThat(answerAttempt.error).isInstanceOf(IllegalStateException.class);
            } else {
                assertThat(answerAttempt.success).isTrue();
                assertThat(nodeRetracted).isFalse();
                assertThat(answerExists).isTrue();
                assertThat(undoAttempt.error).isInstanceOf(IllegalStateException.class);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B2.5: two concurrent undos of a single undoable operation must not act on
     * the same operation twice. The project-row lock serializes them: the first
     * transaction undoes the operation (UNDONE), the second re-reads the stack
     * under the lock and finds nothing left to undo. Exactly one transition
     * occurs.
     */
    @Test
    void concurrentUndoOfSingleOperationDoesNotDoubleApply() throws Exception {
        project = projectService.createProject("并发撤销 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        commandService.createRootDraftNode(project.id(), routeId, "NOTE", Map.of("text", "root"));
        // Exactly one reversible ACTIVE operation exists.

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> a = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> b = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });

            Attempt attemptA = a.get(60, TimeUnit.SECONDS);
            Attempt attemptB = b.get(60, TimeUnit.SECONDS);

            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one concurrent undo may succeed")
                    .isTrue();
            long undoneCount = operationRepository.findByProject(project.id()).stream()
                    .filter(op -> op.status() == GraphOperation.Status.UNDONE)
                    .count();
            assertThat(undoneCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B2.5: Undo racing a live graph mutation (creating a semantic relation)
     * must never produce a half-applied stack. The project-row lock serializes
     * the two transactions, so each observes a consistent operation log. Either
     * the child is retracted and the relation creation refuses the retracted
     * endpoint, or the relation is created on a live child and then undone — but
     * the child is never simultaneously retracted AND referenced by an active
     * relation.
     */
    @Test
    void undoAndConcurrentMutationAreSerializedWithoutHalfAppliedStack() throws Exception {
        project = projectService.createProject("撤销与变更并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), routeId, root.id(), "NOTE", Map.of("text", "child")).node();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> relFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), root.id(), child.id(), NodeRelationType.SUPPORTS,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt relAttempt = relFuture.get(60, TimeUnit.SECONDS);

            // Undo always targets a valid ACTIVE operation, so it succeeds.
            assertThat(undoAttempt.success).isTrue();

            boolean childRetracted = nodeRepository.findById(child.id()).orElseThrow().isRetracted();
            boolean relationActive = !relationRepository.findActiveByProject(project.id()).isEmpty();

            // No half-applied intermediate: a retracted child is never the live
            // endpoint of an active relation.
            assertThat(childRetracted && relationActive)
                    .as("retracted child must not be referenced by an active relation")
                    .isFalse();

            // The relation creation, if it ran, either succeeded (child was live)
            // or failed closed on the retracted endpoint — never a partial state.
            if (relationActive) {
                assertThat(relAttempt.success).isTrue();
                assertThat(childRetracted).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 3 (deep review) — Undo racing appendContinuation. Both writers take
     * the same project-row lock first, so the final materialized graph and the
     * GraphOperation stack are always linear: no ACTIVE creation op can point
     * at a retracted node, and no UNDONE creation op can point at a live one.
     */
    @Test
    void undoAndAppendContinuationLeaveLinearStackAndGraph() throws Exception {
        project = projectService.createProject("撤销与续写并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));
        Node child = commandService.appendContinuation(
                project.id(), routeId, root.id(), "NOTE", Map.of("text", "child")).node();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> appendFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.appendContinuation(
                        project.id(), routeId, child.id(), "NOTE", Map.of("text", "new")));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt appendAttempt = appendFuture.get(60, TimeUnit.SECONDS);

            // The undo always succeeds (there is always a valid ACTIVE op it
            // targets); the continuation either succeeds before the undo (then
            // the undo rolls it back) or fails after it (child no longer on
            // the tip lineage). Never a half-applied intermediate.
            assertThat(undoAttempt.success).isTrue();
            assertLinearOperationStack(project.id());
            assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 3 (deep review) — Undo racing reviseDraftNode. The project row lock
     * serializes the two transactions: either the revision lands first and the
     * undo restores the prior content, or the undo lands first and the
     * revision applies on the restored state. The final node content must be
     * exactly one of the two serial orders and the operation log must stay a
     * consistent linear stack.
     */
    @Test
    void undoAndReviseDraftNodeNeverLoseOrDoubleApplyARevision() throws Exception {
        project = projectService.createProject("撤销与编辑并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "v0"));
        commandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "v1"));

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> reviseFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.reviseDraftNode(
                        project.id(), draft.id(), "NOTE", Map.of("text", "v2")));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt reviseAttempt = reviseFuture.get(60, TimeUnit.SECONDS);

            assertThat(undoAttempt.success).isTrue();
            assertLinearOperationStack(project.id());

            // The draft itself must never be retracted by these two operations.
            assertThat(nodeRepository.findById(draft.id()).orElseThrow().isRetracted()).isFalse();
            // Final content is one of the serial outcomes: v1 (undo after
            // revise) or v2 (revise after undo). "v0" would mean the revision
            // was silently lost.
            String finalText = (String) nodeRepository.findById(draft.id())
                    .orElseThrow().content().get("text");
            assertThat(finalText).isIn("v1", "v2");
            // The last ACTIVE EDIT op must describe the content that is live.
            var activeEdit = operationRepository.findByProject(project.id()).stream()
                    .filter(op -> op.type() == GraphOperation.Type.EDIT_DRAFT_NODE)
                    .filter(op -> op.status() == GraphOperation.Status.ACTIVE)
                    .reduce((first, second) -> second);
            if (activeEdit.isPresent()) {
                assertThat(activeEdit.get().afterRefs().get("content"))
                        .isEqualTo(Map.of("text", finalText));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 3 (deep review) — Undo racing an accepted CREATE_NODE proposal.
     * Acceptance now takes the project lock (project → proposal → graph) just
     * like every other user-visible graph writer, so a stale accept can never
     * resurrect a retracted anchor: either the acceptance lands first and the
     * later undo hits the non-reversible ACCEPT barrier, or the undo lands
     * first and the acceptance fails stale. The proposal bar is never crossed.
     */
    @Test
    void undoAndAcceptedCreateNodeNeverResurrectRetractedAnchor() throws Exception {
        project = projectService.createProject("撤销与接受提案并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = commandService.createRootDraftNode(
                project.id(), routeId, "NOTE", Map.of("text", "root"));

        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "agent 结论")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of());
        AgentProposal pending = proposalService.createProposal(
                proposal, UUID.randomUUID(), project.id(), routeId);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> undoFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> undoRedoService.undo(project.id()));
            });
            Future<Attempt> acceptFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> acceptanceService.acceptAndExecute(pending.id(), "user"));
            });

            Attempt undoAttempt = undoFuture.get(60, TimeUnit.SECONDS);
            Attempt acceptAttempt = acceptFuture.get(60, TimeUnit.SECONDS);

            assertLinearOperationStack(project.id());
            // The proposal was decided exactly once, never resurrected.
            ProposalStatus finalStatus = proposalService.getProposal(pending.id())
                    .orElseThrow().status();

            if (acceptAttempt.success) {
                // Acceptance won the lock: the produced node is live and the
                // undo then hit the non-reversible ACCEPT barrier.
                assertThat(finalStatus).isEqualTo(ProposalStatus.ACCEPTED);
                assertThat(undoAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("不可撤销");
                assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isFalse();
                assertThat(operationRepository.findByProject(project.id()).stream()
                        .anyMatch(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL
                                && op.status() == GraphOperation.Status.ACTIVE)).isTrue();
            } else {
                // Undo won the lock: the anchor was retracted before the
                // acceptance re-validated, so acceptance failed stale.
                assertThat(finalStatus).isEqualTo(ProposalStatus.PROPOSED);
                assertThat(acceptAttempt.error).isInstanceOf(StaleProposalException.class);
                assertThat(nodeRepository.findById(root.id()).orElseThrow().isRetracted()).isTrue();
                assertThat(operationRepository.findByProject(project.id()).stream()
                        .noneMatch(op -> op.type() == GraphOperation.Type.ACCEPT_AGENT_PROPOSAL)).isTrue();
            }
            // The anchor's route tip is never a retracted node.
            Route route = routeRepository.findById(routeId).orElseThrow();
            if (route.tipNodeId() != null) {
                assertThat(nodeRepository.findById(route.tipNodeId())
                        .orElseThrow().isRetracted()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Linear-stack invariant after any race: a node-creation operation that is
     * ACTIVE must reference a live (non-retracted) node, and one that is
     * UNDONE must reference a retracted node. Any violation would mean a
     * half-applied undo/mutation interleave.
     */
    private void assertLinearOperationStack(UUID projectId) {
        for (GraphOperation op : operationRepository.findByProject(projectId)) {
            boolean creationOp = switch (op.type()) {
                case CREATE_DRAFT_NODE, APPEND_CONTINUATION, ATTACH_RESOURCE,
                     CREATE_BRANCH_AND_APPEND -> true;
                default -> false;
            };
            if (!creationOp || !(op.afterRefs().get("nodeId") instanceof String nodeId)) {
                continue;
            }
            Node node = nodeRepository.findById(UUID.fromString(nodeId)).orElse(null);
            if (node == null) {
                continue;
            }
            boolean active = op.status() == GraphOperation.Status.ACTIVE;
            assertThat(node.isRetracted())
                    .as("linear stack: op %s on node %s status %s", op.type(), nodeId, op.status())
                    .isEqualTo(!active);
        }
    }

    /** One racer's outcome: success, or the exact exception it failed with. */
    private record Attempt(boolean success, Throwable error) {
        static Attempt run(Callable<?> action) {
            try {
                action.call();
                return new Attempt(true, null);
            } catch (Throwable t) {
                return new Attempt(false, t);
            }
        }
    }
}
