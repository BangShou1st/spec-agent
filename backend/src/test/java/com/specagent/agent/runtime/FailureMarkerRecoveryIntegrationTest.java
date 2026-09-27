package com.specagent.agent.runtime;

import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:FailureMarkerRecoveryIntegrationTest.java
 *
 * 测试目标:确定性失败注入(DeterministicEngineFaultPlan 的
 * [[fail-state-update:N]] 指令,既有仅测试基础设施)与任务级恢复链路的
 * 端到端集成:真实回答 run 在"Answer 已持久化之后"被精确打断 → 未解决
 * 失败清单给出 CONTINUE_PROCESSING → 重试映射为 RESUME_ANSWER(Answer
 * 不重复创建)→ 重试执行成功(指令预算已消耗)→ 失败从清单消失。
 * 浏览器验证复用同一指令机制。
 */
@SpringBootTest
@ActiveProfiles("test")
class FailureMarkerRecoveryIntegrationTest {

    @Autowired ProjectService projectService;
    @Autowired NodeService nodeService;
    @Autowired RunService runService;
    @Autowired AgentRunService agentRunService;
    @Autowired AgentRunEventService eventService;
    @Autowired AgentRunRecoveryService recoveryService;
    @Autowired RunWorker worker;
    @Autowired com.specagent.workspace.project.ProjectDeletionService projectDeletionService;

    private final java.util.List<UUID> createdProjects = new java.util.ArrayList<>();

    private Project newProject() {
        Project p = projectService.createProject("标记失败恢复 " + UUID.randomUUID());
        createdProjects.add(p.id());
        return p;
    }

    @AfterEach
    void cleanUp() {
        // 本类不用测试事务(worker 的 REQUIRES_NEW 终态化必须看到已提交行),
        // 数据直接提交;逐项目清理,避免污染共享测试库。
        for (UUID projectId : createdProjects) {
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

    @Test
    void markerInjectedAnswerFailureIsRecoverableWithoutDuplicateAnswer() {
        Project project = newProject();
        UUID routeId = project.activeRouteId();
        Node node = nodeService.createRootNode(project.id(), routeId,
                "What is the goal?", null, List.of(), true);

        // 提交携带确定性失败指令的回答(见 DeterministicEngineFaultPlan):
        // 真实 Answer 先落库,随后该节点的下一次 STATE_UPDATE 被注入失败。
        AgentRun failed = runService.createQueuedRunWithInputResultForRoute(
                project.id(), "ANSWER_TIP", node.id(), null, null,
                "回答内容 [[fail-state-update:1]]", null,
                null, null, null, routeId);
        var claimed = runService.claimAnswerCycleRun(failed.id()).orElseThrow();
        // worker 在终态化失败 run 后会重抛异常(fail-closed 入口契约)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");

        AgentRun failedRun = agentRunService.getRun(failed.id()).orElseThrow();
        assertThat(failedRun.status()).isEqualTo(AgentRunStatus.FAILED);
        // 关键前提:失败发生在 Answer 已持久化之后
        assertThat(failedRun.producedAnswerId()).isNotNull();

        // 读侧:服务端判定 CONTINUE_PROCESSING
        var views = recoveryService.listUnresolved(project.id());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).availableAction()).isEqualTo("CONTINUE_PROCESSING");
        assertThat(views.get(0).runId()).isEqualTo(failed.id().toString());

        // 提交侧重试:映射为 RESUME_ANSWER,复用同一个 Answer
        AgentRun retry = recoveryService.retry(project.id(), failed.id());
        assertThat(retry.operation()).isEqualTo("RESUME_ANSWER");
        assertThat(retry.idempotencyKey()).isEqualTo("retry:" + failed.id());

        // 重试执行:标记已消耗,STATE_UPDATE 通过;Answer 总数仍为 1
        var retryClaimed = runService.claimAnswerCycleRun(retry.id()).orElseThrow();
        worker.executeRun(retryClaimed);
        assertThat(agentRunService.getRun(retry.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        long answerCount = jdbcCountAnswers(project.id());
        assertThat(answerCount).isEqualTo(1);

        // 失败已被成功取代:清单为空
        assertThat(recoveryService.listUnresolved(project.id())).isEmpty();
    }

    private long jdbcCountAnswers(UUID projectId) {
        return jdbcCount("answers", projectId);
    }

    @Autowired org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private long jdbcCount(String table, UUID projectId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE project_id = :projectId",
                Map.of("projectId", projectId), Long.class);
        return v == null ? 0 : v;
    }
}
