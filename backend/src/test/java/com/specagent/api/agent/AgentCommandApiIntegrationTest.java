package com.specagent.api.agent;

import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
 * Question-draft cutover integration tests: the real
 * {@code POST /api/v1/projects/{id}/agent-runs} endpoint returns 202 + runId
 * for {@code DRAFT_QUESTION}, the background worker executes the single-
 * DECISION cycle, and the produced question node lands on the active route
 * (root node on an empty route, tip child afterwards).
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

        // The HTTP command returned before any model work happened: the run
        // is still queued at this point.
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
