package com.specagent.workspace.project;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:ProjectDeletionRunLifecycleGuardTest.java
 *
 * 测试目标:运行中项目删除保护的真实生命周期回归。所有 run 都通过生产
 * 路径构造:RunService 入队(创建态)、生产认领方法(运行态)、
 * AgentRunService 的状态推进方法(处理中间态与终态)。此前测试直接向
 * 数据库手写大写 'RUNNING',与 AgentRunStatus.code() 的小写存储不一致,
 * 保护查询与测试同时失真,删除运行中项目会返回 204。
 *
 * 覆盖:created / running / context_built / model_called / reflected /
 * persisted 全部非终态拒绝删除;completed / failed 终态允许删除;
 * 删除与入队在项目行锁上串行化——删除提交后不可能再入队新任务,
 * 入队先于删除提交则删除必须看到它并以 409 拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectDeletionRunLifecycleGuardTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProjectService projects;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectDeletionService deletion;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;

    private int countRuns(UUID projectId) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE project_id = :projectId",
                Map.of("projectId", projectId), Integer.class);
        return v == null ? 0 : v;
    }

    /** 生产入队 + 生产认领:把一条 run 推进到指定的非终态/终态状态。 */
    private AgentRun runInState(UUID projectId, AgentRunStatus status) {
        AgentRun run = runService.createQueuedDraftQuestion(projectId);
        if (status == AgentRunStatus.CREATED) {
            return run;
        }
        AgentRun claimed = runService.claimDecisionCycleRun(run.id())
                .orElseThrow(() -> new IllegalStateException("claim failed for " + run.id()));
        if (status == AgentRunStatus.RUNNING) {
            return claimed;
        }
        if (status == AgentRunStatus.COMPLETED) {
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "test");
        } else if (status == AgentRunStatus.FAILED) {
            agentRunService.fail(run.id(), "failed:test");
        } else if (status == AgentRunStatus.CONTEXT_BUILT) {
            agentRunService.attachContext(run.id(), null, "test");
        } else if (status == AgentRunStatus.MODEL_CALLED) {
            agentRunService.markModelCalled(run.id(), "test");
        } else if (status == AgentRunStatus.REFLECTED) {
            agentRunService.markReflected(run.id(), "test");
        } else if (status == AgentRunStatus.PERSISTED) {
            agentRunService.complete(run.id(), AgentRunStatus.PERSISTED, "test");
        }
        return agentRunService.getRun(run.id()).orElseThrow();
    }

    @Test
    @Transactional
    void everyNonTerminalStatusBlocksDeletion() throws Exception {
        for (AgentRunStatus status : Arrays.stream(AgentRunStatus.values())
                .filter(s -> s != AgentRunStatus.COMPLETED && s != AgentRunStatus.FAILED)
                .toArray(AgentRunStatus[]::new)) {
            var p = projects.createProject("Guard " + status.name());
            runInState(p.id(), status);
            assertThat(agentRunService.getRun(
                    agentRunService.listActiveByProject(p.id()).get(0).id()).orElseThrow().status())
                    .isEqualTo(status);
            mockMvc.perform(delete("/api/v1/projects/{id}", p.id()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PROJECT_HAS_RUNNING_RUNS"));
            assertThat(countRuns(p.id())).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM projects WHERE id = :projectId",
                    Map.of("projectId", p.id()), Integer.class)).isOne();
        }
    }

    @Test
    @Transactional
    void terminalStatusesAllowDeletion() throws Exception {
        for (AgentRunStatus status : new AgentRunStatus[] {
                AgentRunStatus.COMPLETED, AgentRunStatus.FAILED}) {
            var p = projects.createProject("Terminal " + status.name());
            runInState(p.id(), status);
            mockMvc.perform(delete("/api/v1/projects/{id}", p.id()))
                    .andExpect(status().isNoContent());
            assertThat(countRuns(p.id())).isZero();
        }
    }

    /**
     * 删除与入队的并发边界:删除事务持有项目行锁期间,并发入队被阻塞;
     * 删除提交后入队观察到项目已消失,以"项目不存在"失败——新任务
     * 绝不会被插入到一个已删除的项目里。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedDeleteBlocksConcurrentEnqueue() throws Exception {
        var p = projects.createProject("Delete race");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> deleting = executor.submit(() -> txTemplate.execute(tx -> {
                projectRepository.lockById(p.id());

                deletion.deleteProject(p.id());
                lockAcquired.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> runService.createQueuedDraftQuestion(p.id()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Project not found");
            release.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM projects WHERE id = :projectId",
                    Map.of("projectId", p.id()), Integer.class)).isZero();
            assertThat(countRuns(p.id())).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 反向并发边界:入队先在项目行锁内提交,随后到达的删除必须看到
     * 这条非终态 run 并以 409 拒绝,任何项目数据都不能被部分删除。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void enqueuedRunCommitBlocksSubsequentDelete() {
        var p = projects.createProject("Enqueue race");
        txTemplate.execute(tx -> {
            projectRepository.lockById(p.id());

            runService.createQueuedDraftQuestion(p.id());
            return null;
        });
        assertThatThrownBy(() -> deletion.deleteProject(p.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-terminal agent runs");
        assertThat(countRuns(p.id())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM routes WHERE project_id = :projectId",
                Map.of("projectId", p.id()), Integer.class)).isPositive();
        // 清理:终态化后可删除
        jdbc.update("UPDATE agent_runs SET status = 'failed', completed_at = NOW() "
                + "WHERE project_id = :projectId", Map.of("projectId", p.id()));
        deletion.deleteProject(p.id());
    }

    /**
     * 防漂移守卫:删除保护内联维护的非终态状态码清单(小写)必须与
     * AgentRunStatus 枚举的非终态成员完全一致。新增非终态状态而忘记
     * 更新删除保护时,本测试失败。
     */
    @Test
    void deletionGuardStatusListMatchesEnum() {
        var expected = Arrays.stream(AgentRunStatus.values())
                .filter(s -> s != AgentRunStatus.COMPLETED && s != AgentRunStatus.FAILED)
                .map(AgentRunStatus::code)
                .toList();
        assertThat(ProjectDeletionService.NON_TERMINAL_RUN_STATUSES)
                .containsExactlyInAnyOrderElementsOf(expected);
    }
}
