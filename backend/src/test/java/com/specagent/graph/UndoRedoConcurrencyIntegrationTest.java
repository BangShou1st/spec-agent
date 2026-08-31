package com.specagent.graph;

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
