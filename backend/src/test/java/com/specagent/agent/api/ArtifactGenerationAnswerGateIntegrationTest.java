package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunFailureReasons;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:ArtifactGenerationAnswerGateIntegrationTest.java
 *
 * 测试目标:验证制品生成门禁——绝不能发布一份静默遗漏用户已给出答案的文档。
 * 路线 tip 上持久化了 Answer 但没有 AnswerPatch,说明该答案的 STATE_UPDATE 从未完成,
 * 其 claims 不在规格推导所依赖的状态里;命令入口与排队 run 都必须拒绝,
 * 且拒绝信息要指名检查点已支持的恢复方式。
 *
 * 刻意不加 @Transactional(与 TypedRunFailureIntegrationTest 同理):失败终态化与
 * RUN_FAILED 事件在独立的 REQUIRES_NEW 事务内原子提交——这正是第四轮所有权协议的
 * 一致性要求;外层测试事务会让 REQUIRES_NEW 看不到未提交的 run 行,从而掩盖本套件
 * 要断言的那条记录。生产中 claim 事务在 worker 执行之前已经提交,本形态与之完全一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactGenerationAnswerGateIntegrationTest {

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
    private AgentRunService agentRunService;
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

    private Project projectWithTipQuestion() {
        Project project = projectService.createProject("Artifact gate project");
        touchedProjectIds.add(project.id());
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        return project;
    }

    private Answer persistedUnprocessedAnswer(Project project) {
        return answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId(),
                null, "saved answer", "user");
    }

    @Test
    void generationIsRejectedWhileTheTipAnswerHasNoCheckpoint() throws Exception {
        Project project = projectWithTipQuestion();
        persistedUnprocessedAnswer(project);

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"));

        // 什么都没有生成,也没有排队任何可能生成它的 run。
        assertThat(runService.claimNextArtifact()).isEmpty();
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
    }

    @Test
    void generationProceedsOnceTheAnswerHasItsCheckpoint() throws Exception {
        Project project = projectWithTipQuestion();
        Answer answer = persistedUnprocessedAnswer(project);
        answerPatchService.save(project.id(), project.activeRouteId(), answer.nodeId(),
                answer.id(), List.of(new Claim(null, ClaimKind.fromCode("goal"),
                        "The goal is clear.", ClaimStatus.fromCode("confirmed"), 0.9,
                        answer.nodeId(), answer.id())), null);

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted());

        assertThat(runService.claimNextArtifact()).isPresent();
    }

    @Test
    void queuedGenerationFailsClosedWhenTheTipAnswerIsNeverProcessed() throws Exception {
        Project project = projectWithTipQuestion();
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        MvcResult accepted = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accepted.getResponse().getContentAsString()).get("runId").asText();

        // 用户在排队 run 被认领之前提交了答案;该答案的 STATE_UPDATE 从未完成。
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), tipNodeId,
                null, "saved answer", "user");

        AgentRun claimed = runService.claimNextArtifact().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(RuntimeException.class);

        // 持久的 FAILED 状态与 RUN_FAILED 事件在同一 REQUIRES_NEW 事务内
        // 原子提交(第四轮失败一致性协议);这里决定性的证据是记录下来的失败
        // 事件本身,以及"没有任何 spec 快照被生成"。
        AgentRun run = agentRunService.getRun(UUID.fromString(runId)).orElseThrow();
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();

        AgentRunEvent failed = eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .findFirst().orElseThrow();
        assertThat(failed.payload())
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE)
                .containsEntry("errorCode", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
        assertThat((String) failed.payload().get("summary")).isNotBlank();

        // 同样的说明通过白名单进度读模型送达客户端。
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.summary").value(failed.payload().get("summary")));
    }
}
