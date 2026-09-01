package com.specagent.route;

import com.specagent.answer.AnswerService;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Active-route pointer atomicity under concurrency.
 *
 * <p>Two independent transactions race on lifecycle / active-route mutations for
 * the same project. Each operation takes the project row lock
 * ({@code SELECT ... FOR UPDATE} in {@code ProjectRepository.lockById}) so the
 * decision-and-write sequence is serialized: a winner fully commits before the
 * loser even observes project state. The regression this guards is the classic
 * lost-update — without the lock, {@code archiveRoute} could read a stale
 * active pointer, leave it dangling on an archived route, and the project would
 * end up with {@code activeRouteId} pointing at a non-OPEN route.
 *
 * <p>Deliberately NOT {@code @Transactional}: each racer must run in its own
 * real database transaction so the project lock actually serializes them, and
 * the setup rows are committed before the threads start.
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteActivePointerConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private RouteService routeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private GraphCommandService commandService;
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
        // Routes carry FK references into nodes (branch_at / created_from /
        // branch_from), so routes must go before nodes.
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * activate A vs archive A. Whatever commits last, the project must never be
     * left pointing its active route at an archived route. If archive wins, the
     * active pointer is cleared (null); if activate "wins" it only does so before
     * archive ran, after which archive clears the pointer. The final active route
     * is therefore null or — if another OPEN route existed — an OPEN route, never A.
     */
    @Test
    void activateActiveVsArchiveActiveNeverDanglesPointerOnArchivedRoute() throws Exception {
        project = projectService.createProject("激活与归档并发 " + UUID.randomUUID());
        UUID activeRouteId = project.activeRouteId();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureActivate = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.setActiveRoute(project.id(), activeRouteId); return null; });
            });
            Future<Attempt> futureArchive = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), activeRouteId); return null; });
            });

            futureActivate.get(60, TimeUnit.SECONDS);
            futureArchive.get(60, TimeUnit.SECONDS);

            // The archived route is always archived — archive cannot be defeated.
            Route archived = routeRepository.findById(activeRouteId).orElseThrow();
            assertThat(archived.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.ARCHIVED);

            // The active pointer is never left on the archived route.
            Project after = projectRepository.findById(project.id()).orElseThrow();
            assertThat(after.activeRouteId())
                    .as("active pointer must not dangle on the archived route")
                    .isNotEqualTo(activeRouteId);
            if (after.activeRouteId() != null) {
                Route active = routeRepository.findById(after.activeRouteId()).orElseThrow();
                assertThat(active.lifecycleStatus())
                        .as("any remaining active route must be OPEN")
                        .isEqualTo(RouteLifecycleStatus.OPEN);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * restore A vs activate B. A starts archived and B is active. Both end OPEN;
     * whichever write wins the active pointer last, the pointer must still land on
     * an OPEN route — never on the archived-then-restored A in a stale state, and
     * never on a non-OPEN route.
     */
    @Test
    void restoreVsActivateAlwaysLeavesActivePointingAtOpenRoute() throws Exception {
        project = projectService.createProject("恢复与激活并发 " + UUID.randomUUID());
        UUID routeA = project.activeRouteId();
        UUID routeB = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "并发B").id();

        // A is archived and B becomes active, so both racers are meaningful.
        routeService.archiveRoute(project.id(), routeA);
        routeService.setActiveRoute(project.id(), routeB);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureRestore = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.restoreRoute(project.id(), routeA); return null; });
            });
            Future<Attempt> futureActivateB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.setActiveRoute(project.id(), routeB); return null; });
            });

            futureRestore.get(60, TimeUnit.SECONDS);
            futureActivateB.get(60, TimeUnit.SECONDS);

            // Both routes end OPEN.
            assertThat(routeRepository.findById(routeA).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.OPEN);
            assertThat(routeRepository.findById(routeB).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.OPEN);

            // The active pointer must point at an OPEN route, whatever won.
            Project after = projectRepository.findById(project.id()).orElseThrow();
            assertThat(after.activeRouteId()).isNotNull();
            Route active = routeRepository.findById(after.activeRouteId()).orElseThrow();
            assertThat(active.lifecycleStatus())
                    .as("active pointer must point at an OPEN route")
                    .isEqualTo(RouteLifecycleStatus.OPEN);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 4 (deep review) — a fork racing the source-route archive. Both take
     * the project lock first; whatever wins, the archived route is never
     * mutated or resurrected: if archive wins, the fork fails stale on the
     * lifecycle; if the fork wins, the archive still archives the source and
     * the fork simply inherits the frozen immutable nodes. The archived route
     * stays archived either way.
     */
    @Test
    void archiveVsForkNeverMutatesOrResurrectsArchivedRoute() throws Exception {
        project = projectService.createProject("归档与分叉并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> forkFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.forkFromNode(project.id(), routeId, child.id(), "并发分叉"));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt forkAttempt = forkFuture.get(60, TimeUnit.SECONDS);

            // The source route is archived regardless of the race outcome.
            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (forkAttempt.success) {
                // Fork won the lock: its route is OPEN and inherits the frozen
                // immutable nodes; the archive still archived the source.
                Route fork = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
                assertThat(fork.rootNodeId()).isEqualTo(root.id());
                assertThat(fork.tipNodeId()).isEqualTo(child.id());
            } else {
                // Archive won: the fork was rejected on the lifecycle, fail-closed.
                assertThat(forkAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 4 (deep review) — a re-answer racing the source-route archive. Same
     * serialization contract as the fork: the source never ends up both
     * archived and re-answered, and a re-answer can never resurrect it.
     */
    @Test
    void archiveVsReanswerNeverMutatesOrResurrectsArchivedRoute() throws Exception {
        project = projectService.createProject("归档与重答并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> reanswerFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.reanswerFromNode(project.id(), routeId, child.id(), "并发重答"));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt reanswerAttempt = reanswerFuture.get(60, TimeUnit.SECONDS);

            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (reanswerAttempt.success) {
                Route reanswer = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(reanswer.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
            } else {
                assertThat(reanswerAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 4 (deep review) — a replacement commit racing the source-route
     * archive. The commit holds the project lock and re-reads the source under
     * it; an archived source is never superseded or replaced.
     */
    @Test
    void archiveVsReplacementNeverMutatesArchivedRoute() throws Exception {
        project = projectService.createProject("归档与换题并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> archiveFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> { routeService.archiveRoute(project.id(), routeId); return null; });
            });
            Future<Attempt> replacementFuture = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> routeService.commitReplacementFromNode(
                        project.id(), routeId, child.id(), child.id(), null,
                        "Replacement question", "Replacement purpose", List.of(), true));
            });

            archiveFuture.get(60, TimeUnit.SECONDS);
            Attempt replacementAttempt = replacementFuture.get(60, TimeUnit.SECONDS);

            // The source route is archived; a replacement can never supersede or
            // otherwise mutate an archived route.
            assertThat(routeRepository.findById(routeId).orElseThrow().lifecycleStatus())
                    .isEqualTo(RouteLifecycleStatus.ARCHIVED);

            if (replacementAttempt.success) {
                // Replacement won the lock; the archive then still archived the
                // source and the replacement route is the active OPEN route.
                Route replacement = routeRepository.findById(
                        projectRepository.findById(project.id()).orElseThrow().activeRouteId())
                        .orElseThrow();
                assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
            } else {
                assertThat(replacementAttempt.error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exploration source");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Item 4 (deep review) — the replacement decision is frozen against the
     * source tip captured BEFORE the commit; a concurrent continuation that
     * advances the tip must make the replacement commit fail stale instead of
     * superseding a moved route. The frozen expected tip is re-verified under
     * the project lock inside the commit transaction, so there is no
     * check-then-act window.
     */
    @Test
    void replacementCommitFailsStaleWhenConcurrentContinuationMovesTip() throws Exception {
        project = projectService.createProject("换题过期并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        // The decision is frozen against child.id() as the source tip.
        UUID frozenTip = child.id();

        // A continuation advances the tip to a new node BEFORE the commit.
        Node advanced = commandService.appendContinuation(
                project.id(), routeId, child.id(), "NOTE",
                Map.of("text", "advanced")).node();
        assertThat(advanced.id()).isNotEqualTo(frozenTip);

        // The stale commit must be rejected: the frozen tip no longer matches.
        assertThatThrownBy(() -> routeService.commitReplacementFromNode(
                project.id(), routeId, child.id(), frozenTip, null,
                "Replacement question", "Replacement purpose", List.of(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source route tip moved");

        // And the route is untouched: still OPEN with the advanced tip.
        Route source = routeRepository.findById(routeId).orElseThrow();
        assertThat(source.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(source.tipNodeId()).isEqualTo(advanced.id());
    }

    /**
     * Item 4 (deep review) — the commit boundary itself re-verifies the frozen
     * tip under the project lock: when the tip still matches, the replacement
     * commits; when it moved, it fails. This proves the serialization is
     * order-correct under concurrency (tips cannot advance mid-commit).
     */
    @Test
    void replacementCommitTipMatchesUnderLock() throws Exception {
        project = projectService.createProject("换题提交并发 " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Child question", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, child.id(), null, "child answer", "user");

        // Frozen tip matches the live tip; the commit must succeed and supersede
        // the source.
        RegenerateResult result = routeService.commitReplacementFromNode(
                project.id(), routeId, child.id(), child.id(), null,
                "Replacement question", "Replacement purpose", List.of(), true);
        assertThat(result.oldRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);
        assertThat(result.replacementRoute().lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
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
