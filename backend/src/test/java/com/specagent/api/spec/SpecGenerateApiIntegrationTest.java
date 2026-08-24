package com.specagent.api.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec generation cutover integration tests: {@code POST /agent-runs} with
 * {@code operation=GENERATE_ARTIFACT} returns 202 + runId, the worker executes
 * ONE ARTIFACT_GENERATION call, and the derived snapshot is persisted with its
 * run provenance behind the Phase 6.1 spec read API. Grounding stays
 * fail-closed (every section must cite allowed source refs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SpecGenerateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.agent.AgentRunService agentRunService;

    /** Drafts a root question and answers it through the production paths. */
    private Project projectWithAnsweredLineage() {
        Project project = projectService.createProject("Spec generation project");
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "The clarified requirement");
        return project;
    }

    private String enqueueArtifact(Project project) throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("GENERATE_ARTIFACT"))
                .andExpect(jsonPath("$.phase").value("CREATED"))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("runId").asText();
    }

    private void executeQueued(String runId) {
        UUID enqueuedId = UUID.fromString(runId);
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(enqueuedId))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued artifact run " + runId));
        worker.executeRun(claimed);
    }

    @Test
    void generateArtifactRunPersistsDerivedSnapshotWithProvenance() throws Exception {
        Project project = projectWithAnsweredLineage();

        String runId = enqueueArtifact(project);

        // The command returned 202 before any model work happened.
        assertThat(agentRunStatus(runId)).isEqualTo("created");

        executeQueued(runId);

        assertThat(agentRunStatus(runId)).isEqualTo("completed");

        // The produced snapshot is readable through the spec read API with
        // its provenance, grounded sections and unresolved items.
        UUID snapshotId = producedSnapshotId(runId);
        mockMvc.perform(get("/api/v1/specs/{snapshotId}", snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(snapshotId.toString()))
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId")
                        .value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.sections", hasSize(2)))
                .andExpect(jsonPath("$.sections[*].title",
                        org.hamcrest.Matchers.hasItems("Overview", "Open Questions")))
                .andExpect(jsonPath("$.sourceRefs[0].kind").value("context"));
    }

    @Test
    void generatedSnapshotsAreIndependentAndReadableThroughReadApi() throws Exception {
        Project project = projectWithAnsweredLineage();

        String firstRun = enqueueArtifact(project);
        executeQueued(firstRun);
        UUID first = producedSnapshotId(firstRun);

        String secondRun = enqueueArtifact(project);
        executeQueued(secondRun);
        UUID second = producedSnapshotId(secondRun);

        // Each generation produces an independent snapshot; neither is treated
        // as source truth for the other.
        assertThat(second).isNotEqualTo(first);
        for (UUID id : java.util.List.of(first, second)) {
            mockMvc.perform(get("/api/v1/specs/{snapshotId}", id))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/specs",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void generateOnProjectWithoutTipNodeRejected() throws Exception {
        Project project = projectService.createProject("Spec empty project");
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId())
                .isNull();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_TIP_NODE"));
    }

    private String agentRunStatus(String runId) {
        return runById(runId).status().code();
    }

    private UUID producedSnapshotId(String runId) {
        return runById(runId).producedSpecSnapshotId();
    }

    private com.specagent.agent.AgentRun runById(String runId) {
        return agentRunService.getRun(UUID.fromString(runId)).orElseThrow();
    }
}
