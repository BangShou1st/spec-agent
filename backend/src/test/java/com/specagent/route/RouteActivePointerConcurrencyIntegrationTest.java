package com.specagent.route;

import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
