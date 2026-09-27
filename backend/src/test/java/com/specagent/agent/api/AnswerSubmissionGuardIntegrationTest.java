package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:AnswerSubmissionGuardIntegrationTest.java
 *
 * 测试目标:验证答案提交守卫——未完成答案循环的恢复必须复用持久化的 Answer 及其
 * 检查点,且绝不能通过静默重放旧答案来"接受"一份不同的提交。回归背景:tip 已持久化
 * 答案时的重新提交曾被改写成对旧答案的 resume,新提交内容被无痕丢弃,用户输入的约束
 * 从未到达图或生成的规格。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnswerSubmissionGuardIntegrationTest {

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
    private AnswerRepository answerRepository;
    @Autowired
    private RunService runService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RunWorker worker;

    private String body(String operation, UUID nodeId, UUID optionId, String freeText, UUID answerId) {
        StringBuilder json = new StringBuilder("{\"operation\": \"").append(operation).append("\"");
        if (nodeId != null) {
            json.append(", \"nodeId\": \"").append(nodeId).append("\"");
        }
        if (optionId != null) {
            json.append(", \"selectedOptionId\": \"").append(optionId).append("\"");
        }
        if (freeText != null) {
            json.append(", \"freeText\": ").append(quote(freeText));
        }
        if (answerId != null) {
            json.append(", \"answerId\": \"").append(answerId).append("\"");
        }
        return json.append("}").toString();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Answer persistedFreeTextAnswer(Project project, Node node, String freeText) {
        return answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), node.id(), null, freeText, "user");
    }

    private void assertSingleAnswer(UUID routeId, UUID nodeId, String expectedFreeText) {
        List<Answer> answers = answerRepository.findByRouteAndNodeIds(routeId, List.of(nodeId));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo(expectedFreeText);
    }

    @Test
    void differingFreeTextOnAnAnsweredTipIsRejectedWithoutTouchingTheAnswer() throws Exception {
        Project project = projectService.createProject("Guard project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANSWER_TIP", root.id(), null, "edited answer", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        // 没有任何内容被覆盖,也没有排队任何会把旧答案重放并丢弃新文本的 run。
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void identicalFreeTextStillResumesThePersistedAnswer() throws Exception {
        Project project = projectService.createProject("Guard identical");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        MvcResult accepted = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("ANSWER_TIP", root.id(), null, "original answer", null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"))
                .andReturn();

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accepted.getResponse().getContentAsString()).get("runId").asText();
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().producedAnswerId())
                .isEqualTo(persisted.id());
    }

    @Test
    void contentlessRetryStillResumesThePersistedAnswer() throws Exception {
        Project project = projectService.createProject("Guard contentless");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANSWER_TIP", root.id(), null, null, null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"));

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        Answer stored = answerRepository
                .findByRouteAndNodeIds(project.activeRouteId(), List.of(root.id())).get(0);
        assertThat(stored.id()).isEqualTo(persisted.id());
    }

    @Test
    void resumeWithoutAnAnswerIdResolvesThePersistedAnswerInsteadOfSubmittingASecond() throws Exception {
        Project project = projectService.createProject("Guard implicit resume");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", root.id(), null, null, null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"));

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(root.id())).get(0).id())
                .isEqualTo(persisted.id());
    }

    @Test
    void changedSelectionIsRejectedAndThePersistedChoiceIsKept() throws Exception {
        Project project = projectService.createProject("Guard selection");
        NodeOption optionA = NodeOption.of("A", null);
        NodeOption optionB = NodeOption.of("B", null);
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Pick one?", null, List.of(optionA, optionB), true);
        answerService.finalizeAnswerWithSelections(project.id(), project.activeRouteId(),
                root.id(), List.of(optionA.id().toString()), null, "user");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANSWER_TIP", root.id(), optionB.id(), null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        assertThat(runService.claimNextAnswerCycle()).isEmpty();
        assertThat(answerService.findAnswerForNode(project.activeRouteId(), root.id())
                .orElseThrow().selectedOptionIds())
                .containsExactly(optionA.id().toString());
    }

    @Test
    void unchangedSelectionStillResumes() throws Exception {
        Project project = projectService.createProject("Guard selection resume");
        NodeOption optionA = NodeOption.of("A", null);
        NodeOption optionB = NodeOption.of("B", null);
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Pick one?", null, List.of(optionA, optionB), true);
        answerService.finalizeAnswerWithSelections(project.id(), project.activeRouteId(),
                root.id(), List.of(optionA.id().toString()), null, "user");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANSWER_TIP", root.id(), optionA.id(), null, null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"));

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(root.id()))).hasSize(1);
    }

    @Test
    void resumeByIdWithoutNodeIdStillEnforcesTheContentGuard() throws Exception {
        // P1-A:answerId 本身就是完整的身份;省略可选的 nodeId 不得绕过内容守卫,
        // 否则 worker 会静默重放持久化的答案并丢弃新文本。
        Project project = projectService.createProject("Guard by answer id");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", null, null, "edited answer", persisted.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        // 没有任何内容被覆盖,也没有排队重放旧答案的 run。
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void resumeByIdWithoutNodeIdAcceptsTheIdenticalResubmission() throws Exception {
        // 相同请求形态且内容一致时仍是合法恢复:身份无歧义、内容匹配,
        // 检查点恢复继续进行,不产生第二条答案。
        Project project = projectService.createProject("Guard by answer id identical");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        MvcResult accepted = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("RESUME_ANSWER", null, null, "original answer", persisted.id())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"))
                .andReturn();

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accepted.getResponse().getContentAsString()).get("runId").asText();
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().producedAnswerId())
                .isEqualTo(persisted.id());
    }

    @Test
    void contentlessResumeByIdWithoutNodeIdStillResumesThePersistedAnswer() throws Exception {
        // 仅凭 answerId 的纯恢复(不带任何内容)仍是受支持的恢复入口。
        Project project = projectService.createProject("Guard by answer id contentless");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", null, null, null, persisted.id())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("RESUME_ANSWER"));

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
        Answer stored = answerRepository
                .findByRouteAndNodeIds(project.activeRouteId(), List.of(root.id())).get(0);
        assertThat(stored.id()).isEqualTo(persisted.id());
    }

    @Test
    void crossRouteAnswerIdIsRejectedWithoutSideEffects() throws Exception {
        // 属于其他路线的 answerId 必须按其自身原因被拒绝——
        // 绝不能被静默解析到激活路线的 tip 上。
        Project project = projectService.createProject("Guard cross-route id");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");
        // 分叉把项目的激活指针移到分支上;答案仍归源路线所有。
        routeService.forkFromNode(project.id(), project.activeRouteId(),
                root.id(), "fork route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", null, null, null, persisted.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_ROUTE_MISMATCH"));

        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void resumeByIdWithMismatchedNodeIdIsRejected() throws Exception {
        // answerId 属于节点 X 而 nodeId 指向节点 Y 是不一致的身份:拒绝而不是猜测。
        Project project = projectService.createProject("Guard mismatched ids");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");
        nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Child?", null, List.of(), true);
        UUID childId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", childId, null, null, persisted.id())))
                .andExpect(status().isConflict());

        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void changedContentOnAHistoricalAnswerIsRejectedWithoutSideEffects() throws Exception {
        Project project = projectService.createProject("Guard stale answer");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        persistedFreeTextAnswer(project, root, "root answer");
        nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Child?", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ANSWER_TIP", root.id(), null, "again", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId())
                .isNotEqualTo(root.id());
    }
}
