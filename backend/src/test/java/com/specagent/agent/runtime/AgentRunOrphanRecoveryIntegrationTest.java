package com.specagent.agent.runtime;

import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AgentRunOrphanRecoveryIntegrationTest.java
 *
 * 测试目标:进程中断后的工作区 AgentRun 孤儿恢复。所有 run 都通过生产
 * 路径构造(RunService 入队、生产认领、AgentRunService 状态推进),模拟
 * 旧进程在 running/context_built/model_called/reflected/persisted 各阶段
 * 崩溃后遗留的行;恢复由新进程启动时的 {@link AgentRunOrphanRecoveryService}
 * 完成。
 *
 * 覆盖:各非终态被诚实终态化为失败;终态 run 不被触碰;created(仅排队,
 * 未被认领)不受影响;重复恢复幂等;RUN_FAILED 事件携带可读说明;
 * 恢复后 API 层(单个查询/活跃列表)正确反映失败状态,不再出现 500 或
 * 幽灵进行中任务;不产生自治续跑、不重写不可变 Answer。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentRunOrphanRecoveryIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProjectService projects;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired AgentRunOrphanRecoveryService recovery;
    @Autowired AgentRunEventService eventService;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final java.util.List<java.util.UUID> touchedProjectIds = new java.util.ArrayList<>();

    private Project createTrackedProject(String title) {
        Project p = projects.createProject(title);
        touchedProjectIds.add(p.id());
        return p;
    }

    @AfterEach
    void cleanUpCommittedRows() {
        // 本类不使用测试事务(恢复路径涉及独立事务),数据直接提交到共享
        // 测试库。清理本类创建的项目名下的 run 行,避免遗留的 created 状态
        // run 污染其他测试的共享队列断言(如 claimNext 的"队列为空")。
        for (UUID projectId : touchedProjectIds) {
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    Map.of("projectId", projectId));
            jdbc.update("UPDATE agent_runs SET parent_run_id = NULL, root_run_id = NULL "
                    + "WHERE project_id = :projectId", Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId",
                    Map.of("projectId", projectId));
        }
    }

    private AgentRun claimAndAdvance(UUID projectId, AgentRunStatus target) {
        AgentRun run = runService.createQueuedDraftQuestion(projectId);
        runService.claimDecisionCycleRun(run.id())
                .orElseThrow(() -> new IllegalStateException("claim failed: " + run.id()));
        switch (target) {
            case RUNNING -> { /* 刚认领 */ }
            case CONTEXT_BUILT -> agentRunService.attachContext(run.id(), null, "crash-test");
            case MODEL_CALLED -> {
                agentRunService.attachContext(run.id(), null, "crash-test");
                agentRunService.markModelCalled(run.id(), "crash-test");
            }
            case REFLECTED -> {
                agentRunService.attachContext(run.id(), null, "crash-test");
                agentRunService.markModelCalled(run.id(), "crash-test");
                agentRunService.markReflected(run.id(), "crash-test");
            }
            case PERSISTED -> agentRunService.complete(run.id(), AgentRunStatus.PERSISTED,
                    "crash-test");
            default -> throw new IllegalArgumentException("unsupported: " + target);
        }
        return agentRunService.getRun(run.id()).orElseThrow();
    }

    @Test
    void orphanRunsAtEveryProcessingStageAreTerminalizedAsFailed() {
        var p = createTrackedProject("Orphan recovery " + UUID.randomUUID());
        // 非终态孤儿:崩溃在各处理阶段
        UUID runningId = claimAndAdvance(p.id(), AgentRunStatus.RUNNING).id();
        UUID contextBuiltId = claimAndAdvance(p.id(), AgentRunStatus.CONTEXT_BUILT).id();
        UUID modelCalledId = claimAndAdvance(p.id(), AgentRunStatus.MODEL_CALLED).id();
        UUID reflectedId = claimAndAdvance(p.id(), AgentRunStatus.REFLECTED).id();
        UUID persistedId = claimAndAdvance(p.id(), AgentRunStatus.PERSISTED).id();
        // 排队但未被认领:不是孤儿,应保持排队
        UUID queuedId = runService.createQueuedDraftQuestion(p.id()).id();
        // 终态 run:恢复绝不能触碰
        UUID completedId = claimAndAdvance(p.id(), AgentRunStatus.PERSISTED).id();
        agentRunService.complete(completedId, AgentRunStatus.COMPLETED, "done");

        // 共享测试库中可能存在其他测试遗留的非终态行:用增量而非绝对值断言
        int recovered = recovery.recoverOrphans().recovered();
        assertThat(recovered).isGreaterThanOrEqualTo(5);

        for (UUID orphanId : new UUID[] {runningId, contextBuiltId, modelCalledId,
                reflectedId, persistedId}) {
            AgentRun failed = agentRunService.getRun(orphanId).orElseThrow();
            assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(failed.completedAt()).isNotNull();
            List<AgentRunEvent> events = eventService.findByRunId(orphanId);
            assertThat(events).extracting(AgentRunEvent::eventType).contains("RUN_FAILED");
        }
        // 排队中的 run 保持原状,等待本进程 worker 认领
        assertThat(agentRunService.getRun(queuedId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);
        // 终态 run 原样保留
        AgentRun untouched = agentRunService.getRun(completedId).orElseThrow();
        assertThat(untouched.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(untouched.completedAt()).isNotNull();

        // 重复恢复(模拟再次重启):对本次构造的孤儿是幂等的——上一轮已把
        // 它们终态化,这一轮不再有属于本测试的失败写入。
        int second = recovery.recoverOrphans().recovered();
        assertThat(second).isLessThanOrEqualTo(recovered - 5);
    }

    @Test
    void recoveredRunIsVisibleAsFailureThroughApiAndActiveList() throws Exception {
        var p = createTrackedProject("Recovery API view " + UUID.randomUUID());
        UUID orphanId = claimAndAdvance(p.id(), AgentRunStatus.MODEL_CALLED).id();
        assertThat(recovery.recoverOrphans().recovered()).isPositive();

        // 单个 run 查询:失败终态,不再处于处理中
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}", p.id(), orphanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(orphanId.toString()))
                .andExpect(jsonPath("$.status").value("failed"));
        // 活跃列表:不再包含该 run,刷新页面不会重建幽灵进度
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/active", p.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runId == '" + orphanId + "')]").isEmpty());
    }

    @Test
    void recoveryDoesNotReplayAnswersOrContinuations() {
        var p = createTrackedProject("No replay " + UUID.randomUUID());
        UUID orphanId = claimAndAdvance(p.id(), AgentRunStatus.REFLECTED).id();
        long answersBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE project_id = :projectId",
                Map.of("projectId", p.id()), Long.class);
        long checksBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_continuation_checks WHERE run_id = :runId",
                Map.of("runId", orphanId), Long.class);

        assertThat(recovery.recoverOrphans().recovered()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE project_id = :projectId",
                Map.of("projectId", p.id()), Long.class)).isEqualTo(answersBefore);
        // 没有为中断的 run 自动派发续跑子任务
        assertThat(runService.getRun(orphanId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(agentRunRepositoryChildCount(orphanId)).isZero();
        assertThat(checksBefore).isZero();
    }

    private long agentRunRepositoryChildCount(UUID parentId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = :parentId",
                Map.of("parentId", parentId), Long.class);
        return v == null ? 0 : v;
    }
}
