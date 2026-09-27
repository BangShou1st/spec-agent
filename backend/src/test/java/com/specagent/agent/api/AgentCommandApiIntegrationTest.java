package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:AgentCommandApiIntegrationTest.java
 *
 * 测试目标:问题草稿(question-draft)切换后的 API 集成测试——真实的
 * {@code POST /api/v1/projects/{id}/agent-runs} 端点对 {@code DRAFT_QUESTION} 返回
 * 202 + runId 且 run 先处于排队状态,后台 worker 执行单 DECISION 循环后,产出的问题
 * 节点落在激活路线上(空路线为根节点,已有 tip 则为其子节点);响应不得暴露任何
 * 原始模型材料。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentCommandApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private com.specagent.agent.runtime.RunWorker worker;
    @Autowired
    private com.specagent.agent.runtime.RunService runService;

    private static final String FAKE_QUESTION = "What is the most important outcome?";

    @Test
    void draftQuestionRunCreatesRootNodeAndRun() throws Exception {
        Project project = projectService.createProject("Draft project");

        String runId = enqueueDraft(project);

        // HTTP 命令在任何模型工作发生之前就返回了:此刻 run 仍处于排队状态。
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);

        var claimed = runService.claimNext().orElseThrow();
        worker.executeRun(claimed);

        UUID producedNodeId = agentRunService.getRun(UUID.fromString(runId)).orElseThrow()
                .producedNodeId();
        Node produced = nodeService.getNode(producedNodeId).orElseThrow();
        assertThat(produced.projectId()).isEqualTo(project.id());
        assertThat(produced.parentNodeId()).isNull();
        assertThat(produced.question()).isEqualTo(FAKE_QUESTION);

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.operation").value("DRAFT_QUESTION"))
                .andExpect(jsonPath("$.producedNodeId").value(producedNodeId.toString()));
    }

    @Test
    void draftQuestionRunResponseExposesNoRawModelMaterial() throws Exception {
        Project project = projectService.createProject("Draft safety");

        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"DRAFT_QUESTION\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        String body = created.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("inputJson")
                .doesNotContain("outputJson")
                .doesNotContain("\"context\":{")
                .doesNotContain("provider")
                .doesNotContain("Bearer");
    }

    @Test
    void draftQuestionRunCreatesChildWhenTipExists() throws Exception {
        Project project = projectService.createProject("Draft child project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        // 未回答的 Question 必须保持为路线 tip;
        // 先回答根节点,下一轮草稿才能追加子节点。
        var answer = answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "answered root", "test-user");
        answerPatchService.save(project.id(), project.activeRouteId(), root.id(),
                answer.id(), List.of(), null);

        String runId = enqueueDraft(project);
        var claimed = runService.claimNext().orElseThrow();
        worker.executeRun(claimed);

        UUID producedNodeId = agentRunService.getRun(UUID.fromString(runId)).orElseThrow()
                .producedNodeId();
        Node produced = nodeService.getNode(producedNodeId).orElseThrow();
        assertThat(produced.parentNodeId()).isEqualTo(root.id());
    }

    private String enqueueDraft(Project project) throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"DRAFT_QUESTION\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.operation").value("DRAFT_QUESTION"))
                .andExpect(jsonPath("$.phase").value("CREATED"))
                .andReturn();
        return extractString(created.getResponse().getContentAsString(), "runId");
    }

    private String extractString(String body, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get(field);
        return node.asText();
    }
}
