package com.specagent.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
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
 * Answer production cutover integration tests: the real
 * {@code POST /api/v1/projects/{id}/agent-runs} endpoint returns 202 + runId,
 * the background worker executes the ANSWER_CYCLE, and the run read view
 * exposes real phases plus produced ids. Covers success, resume (exactly one
 * Answer / one patch), stale target rejection and duplicate-answer safety.
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
    private com.specagent.answer.AnswerService answerService;
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

        // The HTTP command returned before any model work happened: the run is
        // still CREATED/queued at this point.
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);

        // Worker claims and executes the queued run.
        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        // Run completed through the full 2-call cycle.
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // Exactly one immutable Answer was persisted.
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

        // First submission completes and persists the Answer.
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

        // Second submission of the same answered node: the original cycle
        // already completed (the tip moved on), so the backend rejects the
        // duplicate synchronously — no second Answer can ever be created.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                + "\", \"freeText\": \"duplicate attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_ALREADY_FINALIZED"));

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

        // Simulate a cycle that persisted the Answer but failed before the
        // tip advanced: finalize an answer directly (the repair gate state).
        var answer = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), tipNodeId, null,
                "saved but unfinished", "user");

        // Re-submission of the still-current answered tip routes to
        // RESUME_ANSWER so the cycle resumes instead of failing.
        MvcResult second = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + tipNodeId
                                        + "\", \"freeText\": \"duplicate attempt\"}"))
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
    void staleNodeTargetIsRejectedAtExecutionTime() throws Exception {
        Project project = projectService.createProject("Stale target project");
        NodeOption option = NodeOption.of("A", null);
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(option), true);
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID staleTipId = route.tipNodeId();

        // Enqueue against the current tip...
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"ANSWER_TIP\", \"nodeId\": \"" + staleTipId
                                + "\", \"freeText\": \"late answer\"}"))
                .andExpect(status().isAccepted());

        // ...then advance the graph before the worker claims the run.
        nodeService.createWorkspaceNode(project.id(), project.activeRouteId(), staleTipId,
                com.specagent.node.NodeKind.KNOWLEDGE, "NOTE",
                java.util.Map.of("text", "user moved on"),
                com.specagent.node.NodeAuthorKind.USER,
                com.specagent.node.KnowledgeStatus.PROPOSED);

        var claimed = runService.claimNextAnswerCycle().orElseThrow();
        try {
            worker.executeRun(claimed);
            org.junit.jupiter.api.Assertions.fail("stale answer target must fail the run");
        } catch (RuntimeException expected) {
            // The worker rethrows after marking the run FAILED.
        }

        // No answer landed on the stale node.
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
                // A has no tip node yet; the enqueue itself still succeeds
                // because validation happens at execution time.
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(
                created.getResponse().getContentAsString(), "runId");

        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        projectB.id(), runId))
                .andExpect(status().isNotFound());
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
