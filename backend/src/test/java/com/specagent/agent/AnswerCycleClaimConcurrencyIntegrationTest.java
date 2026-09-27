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
 * 文件名:AnswerCycleClaimConcurrencyIntegrationTest.java
 *
 * 测试目标:用真实并发验证 ANSWER_CYCLE 的领取所有权(tech-debt #13)。多个消费者
 * (生产 {@code RunWorker} 轮询器、测试驱动、评估 harness)会同时竞争共享队列,领取必须
 * 是原子的:恰好一个消费者赢得某个 run,失败者一无所获,且只有赢家的 run 会转为 RUNNING,
 * 从而保证单个 run 永远不会被两个执行者同时认领。
 *
 * 竞争通过 {@link CyclicBarrier} 制造,绝不使用 sleep 或重试;入队被真实提交
 * (无外层测试事务),使竞争线程在独立连接上争夺同一数据库行。
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

        // 唯一赢家持有 RUNNING 状态的行;后续任何领取都得空。
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
        // routes 引用 nodes(branch_at_node_id),需先删 routes 再删 nodes。
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);
        projectId = null;
    }
}
