package com.specagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AnswerCycleRunApiIntegrationTest.java
 *
 * 测试目标:答案生产(answer)切换后的 API 集成测试——真实的
 * {@code POST /api/v1/projects/{id}/agent-runs} 端点返回 202 + runId,后台 worker 执行
 * ANSWER_CYCLE,run 读视图暴露真实阶段与产出 id。覆盖成功路径、恢复(恰好一条 Answer /
 * 一个补丁)、过期目标拒绝、重复答案安全、无效选项拒绝与跨项目隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnswerCycleRunApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private com.specagent.workspace.answer.AnswerService answerService;
    @Autowired
    private com.specagent.agent.runtime.RunWorker worker;
    @Autowired
    private com.specagent.agent.runtime.RunService runService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerRepository answerRepository;

    @Test
    void createRunReturns202AndExecutesAnswerCycle() throws Exception {
        Project project = projectService.createProject("Answer run api project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "最重要的目标是什么？", null, List.of(), true);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                        + "\", \"freeText\": \"明确首要目标\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.operation").value("ANSWER_TIP"))
                .andExpect(jsonPath("$.phase").value("CREATED"))
                .andReturn();

        String body = created.getResponse().getContentAsString();
        String runId = extractString(body, "runId");

        // HTTP 命令在任何模型工作发生之前就返回了:此刻 run 仍处于 CREATED/排队状态。
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);

        // worker 认领并执行排队的 run。
        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        // run 走完完整的 2 次调用循环后完成。
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // 恰好持久化了一条不可变的 Answer。
        List<Answer> answers = answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(tipNodeId));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("明确首要目标");
        assertThat(answerPatchService.findBySourceAnswerId(answers.get(0).id())).isPresent();
    }

    @Test
    void runReadExposesRealPhaseAndProducedIds() throws Exception {
        Project project = projectService.createProject("Run phase read project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question?", null, List.of(), true);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();
        UUID nodeId = nodeService.createChildNode(project.id(), project.activeRouteId(),
                tipNodeId, "Next question?", null, List.of(), true).id();

        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + nodeId
                                        + "\", \"freeText\": \"answer\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(
                created.getResponse().getContentAsString(), "runId");

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.phase").value("COMPLETED"))
                .andExpect(jsonPath("$.producedAnswerId").exists())
                .andExpect(jsonPath("$.producedPatchId").exists())
                .andExpect(jsonPath("$.producedSpecSnapshotId").doesNotExist())
                .andExpect(jsonPath("$.operation").value("ANSWER_TIP"));
    }

    @Test
    void resubmittingAnsweredNodeRoutesToResumeAndKeepsOneAnswer() throws Exception {
        Project project = projectService.createProject("Resume api project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question?", null, List.of(), true);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        // 第一次提交完成并持久化 Answer。
        MvcResult first = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                        + "\", \"freeText\": \"first\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        String firstRunId = extractString(
                first.getResponse().getContentAsString(), "runId");
        assertThat(agentRunService.getRun(UUID.fromString(firstRunId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        UUID persistedAnswerId = agentRunService.getRun(UUID.fromString(firstRunId))
                .orElseThrow().producedAnswerId();
        assertThat(persistedAnswerId).isNotNull();

        // 对同一个已回答节点再次提交:原循环已完成(tip 已推进),后端同步拒绝
        // 重复提交——绝不允许产生第二条 Answer。
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                + "\", \"freeText\": \"duplicate attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        List<Answer> answers = answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(tipNodeId));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("first");
    }

    @Test
    void resubmittingUnfinishedCycleRoutesToResume() throws Exception {
        Project project = projectService.createProject("Resume unfinished project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question?", null, List.of(), true);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        // 模拟一个持久化了 Answer 但在 tip 推进前失败的循环:
        // 直接定稿一条答案(修复门禁的状态)。
        var answer = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), tipNodeId, null,
                "saved but unfinished", "user");

        // 重新提交相同答案会路由到 RESUME_ANSWER,让循环从自己的检查点恢复而不是失败。
        MvcResult second = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                        + "\", \"freeText\": \"saved but unfinished\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(extractString(second.getResponse().getContentAsString(), "operation"))
                .isEqualTo("RESUME_ANSWER");

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());

        List<Answer> answers = answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(tipNodeId));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).id()).isEqualTo(answer.id());
    }

    @Test
    void resubmittingDifferentContentIsRejectedInsteadOfSilentlyResuming() throws Exception {
        Project project = projectService.createProject("Resume content guard project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question?", null, List.of(), true);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();
        var answer = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), tipNodeId, null,
                "saved but unfinished", "user");

        // 新提交的内容会被恢复流程丢弃(循环重放持久化的答案),所以必须 fail-closed 拒绝。
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                + "\", \"freeText\": \"a different answer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        List<Answer> answers = answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(tipNodeId));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).id()).isEqualTo(answer.id());
        assertThat(answers.get(0).freeText()).isEqualTo("saved but unfinished");
        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void staleNodeTargetIsRejectedAtExecutionTime() throws Exception {
        Project project = projectService.createProject("Stale target project");
        NodeOption option = NodeOption.of("A", null);
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(option), true);
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID staleTipId = route.tipNodeId();

        // 先对当前 tip 入队……
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + staleTipId
                                + "\", \"freeText\": \"late answer\"}"))
                .andExpect(status().isAccepted());

        // ……然后在 worker 认领 run 之前推进图。
        // 派生知识不再顶掉问题 tip,用真正的新问题把 tip 推走。
        nodeService.createChildNode(project.id(), project.activeRouteId(), staleTipId,
                "A newer question", null, List.of(), true);

        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        try {
            worker.executeRun(claimed);
            org.junit.jupiter.api.Assertions.fail("stale answer target must fail the run");
        } catch (RuntimeException expected) {
            // worker 在把 run 标记为 FAILED 后重新抛出。
        }

        // 过期节点上没有落下任何答案。
        List<Answer> answers = answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(staleTipId));
        assertThat(answers).isEmpty();
    }

    @Test
    void invalidSelectedOptionIsRejectedBeforeAnyAnswer() throws Exception {
        Project project = projectService.createProject("Invalid option project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Pick one?", null, List.of(NodeOption.of("Only", null)), false);
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                + "\", \"selectedOptionId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isAccepted());

        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        try {
            worker.executeRun(claimed);
            org.junit.jupiter.api.Assertions.fail("random option id must fail the run");
        } catch (RuntimeException expected) {
            // fail-closed
        }
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(tipNodeId))).isEmpty();
    }

    @Test
    void foreignProjectRunIsNotReadableThroughAnotherProject() throws Exception {
        Project projectA = projectService.createProject("Owner A runs");
        Project projectB = projectService.createProject("Owner B runs");

        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectA.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"freeText\": \"x\"}"))
                // A 还没有 tip 节点;入队本身仍然成功,因为校验发生在执行时。
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(
                created.getResponse().getContentAsString(), "runId");

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        projectB.id(), runId))
                .andExpect(status().isNotFound());
    }

    @Test
    void answerCommandWithoutActiveRouteReturnsConflictInsteadOf500() throws Exception {
        Project project = projectService.createProject("No active route answer project");
        routeService.archiveRoute(project.id(), project.activeRouteId());

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"ANSWER_TIP\",\"freeText\":\"stale answer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_ROUTE"));
    }

    @Test
    void invalidRegenerateRequestWithoutActiveRouteReturnsBadRequestBeforeRouteConflict() throws Exception {
        Project project = projectService.createProject("Invalid regenerate without active route project");
        routeService.archiveRoute(project.id(), project.activeRouteId());

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"REGENERATE_NODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGENERATE_TARGET_REQUIRED"));
    }

    private String extractString(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has(field) && !node.get(field).isNull()
                    ? node.get(field).asText() : null;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read JSON response", ex);
        }
    }
}
