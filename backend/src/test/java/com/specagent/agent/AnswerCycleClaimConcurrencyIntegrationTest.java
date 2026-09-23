package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.AnswerCycleClaimConcurrencyIntegrationTest;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.RunService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-concurrency proof of the ANSWER_CYCLE claim ownership (tech-debt #13).
 *
 * <p>Several consumers hit the shared queue at once (the production
 * {@code RunWorker} poller, a test driver, the eval harness). The claim must be
 * atomic: exactly one consumer wins a given run, the losers get nothing, and
 * only the winner's run transitions to RUNNING — so a single run can never be
 * adopted by two executors.
 *
 * <p>The competition is produced with a {@link CyclicBarrier}, never with
 * sleeps or retries, and the enqueue is committed (no surrounding test
 * transaction) so the racing threads contend over the same database row on
 * separate connections.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnswerCycleClaimConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID projectId;

    @Test
    void concurrentByIdClaimsOfOneQueuedRunHaveExactlyOneOwner() throws Exception {
        Project project = projectService.createProject(
                "claim-race-" + UUID.randomUUID());
        projectId = project.id();
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "并发领取问题？", null, List.of(), true);
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", root.id(), null, "concurrent answer", null);

        int racers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier startLine = new CyclicBarrier(racers);
        List<Optional<AgentRun>> results = new ArrayList<>();
        try {
            List<Future<Optional<AgentRun>>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit((Callable<Optional<AgentRun>>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return runService.claimAnswerCycleRun(runId);
                }));
            }
            for (Future<Optional<AgentRun>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<Optional<AgentRun>> winners = results.stream()
                .filter(Optional::isPresent).toList();
        assertThat(winners)
                .as("exactly one consumer may adopt the run")
                .hasSize(1);
        assertThat(winners.get(0).orElseThrow().id()).isEqualTo(runId);

        // The single winner owns the RUNNING row; every later claim gets nothing.
        assertThat(runService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.RUNNING);
        assertThat(runService.claimAnswerCycleRun(runId))
                .as("the run is no longer claimable once owned")
                .isEmpty();
    }

    @AfterEach
    void cleanUp() {
        if (projectId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update(
                "DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", projectId);
        jdbcTemplate.update(
                "DELETE FROM agent_input_projections WHERE snapshot_id IN (SELECT id FROM context_snapshots WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", projectId);
        // Routes reference nodes (branch_at_node_id); delete routes before nodes.
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);
        projectId = null;
    }
}
