package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.spec.SpecSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:HistoricalRecoveryFaultInjectionIntegrationTest.java
 *
 * 测试目标:历史恢复 UX 所服务的那一种产品状态——"答案已持久化,但其 STATE_UPDATE
 * 检查点缺失"——在构造上无法经公共入口到达(DRAFT 路径拒绝跨越未处理的答案)。因此
 * 浏览器回归必须通过 {@link com.specagent.agent.decision.DeterministicEngineFaultPlan}
 * 精确声明一次失败才能造出该状态。本测试在任何浏览器测试依赖该机制之前,经由真实
 * HTTP 入口端到端验证它:声明的失败只落在被标记答案的循环上,持久化的 Answer 与失败
 * run 记录得以幸存,制品门禁以有界的恢复身份拒绝,随后正式的 RESUME_ANSWER 入口完成
 * 检查点,生成再对真实状态成功执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 刻意不加 @Transactional(与 TypedRunFailureIntegrationTest 同理):失败终态化与
// RUN_FAILED 事件在独立的 REQUIRES_NEW 事务内原子提交——第四轮所有权协议的一致性
// 要求;外层测试事务会让 REQUIRES_NEW 看不到未提交的 run 行,从而掩盖本套件要断言
// 的那条记录。生产中 claim 事务在 worker 执行之前已经提交,本形态与之完全一致。
class HistoricalRecoveryFaultInjectionIntegrationTest {

    private static final String DIRECTIVE = "[[fail-state-update:2]]";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private RunService runService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private final java.util.List<UUID> touchedProjectIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanUpRunRows() {
        for (UUID projectId : touchedProjectIds) {
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    java.util.Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    java.util.Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId",
                    java.util.Map.of("projectId", projectId));
        }
    }

    private UUID startRun(UUID projectId, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("runId").asText());
    }

    private List<Map<String, Object>> failedEvents(UUID runId) {
        return eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .map(AgentRunEvent::payload)
                .toList();
    }

    @Test
    void declaredFailureIsRecoverableThroughTheFormalEntryPoint() throws Exception {
        Project project = projectService.createProject("Fault plan project " + UUID.randomUUID());
        touchedProjectIds.add(project.id());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "会议时长要求是什么？", null, List.of(), true);
        UUID questionNodeId = root.id();

        // ── 1. 答案循环故意失败,仅针对该节点 ──
        UUID answerRunId = startRun(project.id(),
                "{\"operation\": \"ANSWER_TIP\", \"freeText\": \"会议不超过45分钟。" + DIRECTIVE + "\"}",
                202);
        AgentRun claimed = runService.claimAnswerCycleRun(answerRunId).orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");

        // 用户的答案在处理失败后幸存,且没有写入检查点
        // ——这正是恢复流程为之存在的状态。
        Answer answer = answerService.findAnswerForNode(routeId, questionNodeId).orElseThrow();
        assertThat(answer.freeText()).contains("会议不超过45分钟");
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(failedEvents(answerRunId)).isNotEmpty();
        assertThat(failedEvents(answerRunId).get(0))
                .containsEntry("reason", "IllegalStateException");

        // ── 2. 制品门禁以有界的恢复身份拒绝 ──
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").value(answer.id().toString()))
                .andExpect(jsonPath("$.details.routeId").value(routeId.toString()))
                .andExpect(jsonPath("$.details.nodeId").value(questionNodeId.toString()));
        assertThat(specSnapshotService.listByRoute(routeId)).isEmpty();

        String resumeBody = "{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                + answer.id() + "\", \"nodeId\": \"" + questionNodeId
                + "\", \"sourceRouteId\": \"" + routeId + "\"}";

        // ── 3. 第一次恢复尝试仍在声明的失败预算之内 ──
        UUID firstRecovery = startRun(project.id(), resumeBody, 202);
        assertThatThrownBy(() -> worker.executeRun(
                runService.claimAnswerCycleRun(firstRecovery).orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(questionNodeId)))
                .as("a failed recovery never creates a second Answer")
                .hasSize(1);

        // ── 4. 重试:普通运行时完成检查点 ──
        UUID secondRecovery = startRun(project.id(), resumeBody, 202);
        worker.executeRun(runService.claimAnswerCycleRun(secondRecovery).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(answer.id()))
                .as("recovery writes the missing STATE_UPDATE checkpoint")
                .isPresent();
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(questionNodeId)))
                .hasSize(1);
        assertThat(eventService.findByRunId(secondRecovery).stream()
                .map(AgentRunEvent::eventType))
                .contains("RUN_COMPLETED")
                .doesNotContain("HISTORICAL_ANSWER_RECOVERED");

        // ── 5. 门禁放行:生成对真实状态成功执行 ──
        UUID artifactRun = startRun(project.id(),
                "{\"operation\": \"GENERATE_ARTIFACT\"}", 202);
        worker.executeRun(runService.claimArtifactRun(artifactRun).orElseThrow());
        assertThat(specSnapshotService.listByRoute(routeId)).hasSize(1);
    }

    @Test
    void ordinaryAnswersAreUnaffectedByTheFaultPlan() throws Exception {
        Project project = projectService.createProject("No directive project " + UUID.randomUUID());
        touchedProjectIds.add(project.id());
        UUID routeId = project.activeRouteId();
        UUID questionNodeId = nodeService.createRootNode(project.id(), routeId,
                "会议时长要求是什么？", null, List.of(), true).id();

        UUID answerRunId = startRun(project.id(),
                "{\"operation\": \"ANSWER_TIP\", \"freeText\": \"会议不超过45分钟。\"}", 202);
        worker.executeRun(runService.claimAnswerCycleRun(answerRunId).orElseThrow());

        Answer answer = answerService.findAnswerForNode(routeId, questionNodeId).orElseThrow();
        assertThat(answerPatchService.findBySourceAnswerId(answer.id()))
                .as("without the directive the answer cycle behaves exactly as before")
                .isPresent();
        assertThat(failedEvents(answerRunId)).isEmpty();
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId())
                .as("an ordinary answer still advances the route tip")
                .isNotEqualTo(questionNodeId);
    }
}
