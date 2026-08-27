package com.specagent.answer;

import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared-Answer concurrency: two independent transactions finalizing the same
 * canonical Question through two routes must never produce two Answer
 * identities. The node-row lock serializes finalization: exactly one
 * transaction persists, the loser observes the persisted Answer and reaches
 * the SHARED_STATE_DIVERGENCE conflict path.
 *
 * <p>Deliberately NOT {@code @Transactional}: both racers must run in real
 * independent transactions against the database, so the setup rows are
 * committed before the threads start.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnswerFinalizeConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteService routeService;
    @Autowired private AnswerService answerService;
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

    @Test
    void concurrentFinalizeOnSharedQuestionPersistsExactlyOneAnswer() throws Exception {
        project = projectService.createProject("共享回答并发 " + UUID.randomUUID());
        UUID firstRouteId = project.activeRouteId();
        // One canonical Question node reached by two routes.
        Node question = nodeService.createRootNode(
                project.id(), firstRouteId, "共享问题?", null, List.of(), true);
        UUID secondRouteId = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "并发第二条路线").id();
        routeService.updateTip(secondRouteId, question.id(), question.id());

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> futureA = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), firstRouteId, question.id(), null, "回答A", "user"));
            });
            Future<Attempt> futureB = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> answerService.finalizeAnswer(
                        project.id(), secondRouteId, question.id(), null, "回答B", "user"));
            });

            Attempt attemptA = futureA.get(60, TimeUnit.SECONDS);
            Attempt attemptB = futureB.get(60, TimeUnit.SECONDS);

            // Exactly one transaction succeeded.
            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one finalization must succeed")
                    .isTrue();
            // The loser reached the expected conflict path: the canonical
            // Question already carries one immutable Answer identity.
            Attempt loser = attemptA.success ? attemptB : attemptA;
            assertThat(loser.error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHARED_STATE_DIVERGENCE");

            // Exactly one Answer row exists for the canonical node — never two
            // Answer identities.
            assertThat(answerCountFor(question.id())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private long answerCountFor(UUID nodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE node_id = ?", Long.class, nodeId);
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