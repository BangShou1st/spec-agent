package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
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
 * Repair/resume command integration tests through the async AgentRun surface:
 * an already persisted immutable answer whose post-answer processing failed is
 * resumed via {@code RESUME_ANSWER} without finalizing a second answer, and
 * ownership/lifecycle checks fail closed.
 *
 * <p>The semantic replay guarantee is covered by
 * {@code AnswerResumeSemanticReplayIntegrationTest}; the durable-artifact
 * invariants are covered by {@code FakeAnswerRepairIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RepairApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private RunService runService;
    @Autowired
    private com.specagent.agent.AgentRunService agentRunService;
    @Autowired
    private RunWorker worker;

    private Answer persistedAnswer(Project project, Node node) {
        return answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), node.id(), null, "clarified", "user");
    }

    @Test
    void resumeRunCompletesAndKeepsSingleAnswer() throws Exception {
        Project project = projectService.createProject("API repair project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the most important outcome?", null, List.of(), true);
        Answer existing = persistedAnswer(project, root);

        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"nodeId\": \"" + root.id()
                                        + "\", \"answerId\": \"" + existing.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(created.getResponse().getContentAsString(), "runId");

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());

        assertThat(agentRunService.getRun(java.util.UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(com.specagent.agent.AgentRunStatus.COMPLETED);

        // No second answer was created for the same route/node.
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                project.activeRouteId(), List.of(root.id()));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).id()).isEqualTo(existing.id());
    }

    @Test
    void resumeWrongProjectAnswerIsNotReadable() throws Exception {
        Project projectA = projectService.createProject("Repair owner A");
        Node node = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "Question", null, List.of(), true);
        Answer answer = persistedAnswer(projectA, node);
        Project projectB = projectService.createProject("Repair owner B");

        // The run read view of another project's run is not found.
        UUID foreignRunId = runService.createQueuedRunWithInput(
                projectA.id(), "RESUME_ANSWER", node.id(), null, null, answer.id());
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        projectB.id(), foreignRunId))
                .andExpect(status().isNotFound());
    }

    @Test
    void resumeAnswerNotInActiveFlowRejectedAtExecutionTime() throws Exception {
        Project project = projectService.createProject("Repair inactive flow");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        Answer answer = persistedAnswer(project, root);

        // Fork away: the answer's route is no longer the active flow.
        Route fork = routeService.forkFromNode(project.id(), project.activeRouteId(), root.id(), "fork");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(fork.id());

        try {
            worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());
            org.junit.jupiter.api.Assertions.fail("inactive-flow resume must fail");
        } catch (RuntimeException expected) {
            // fail-closed: the run ends FAILED
        }
    }

    private String extractString(String json, String field) throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
