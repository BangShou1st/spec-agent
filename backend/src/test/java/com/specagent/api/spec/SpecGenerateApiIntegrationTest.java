package com.specagent.api.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.api.agent.SpecGenerationResponse;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec generation command integration tests through the orchestrator. The
 * snapshot stays a derived artifact; it is persisted with its run provenance
 * and readable through the Phase 6.1 spec read API.
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
    private com.specagent.agent.DecisionCycleTestDriver draftDriver;

    private Project projectWithAnsweredLineage() throws Exception {
        Project project = projectService.createProject("Spec generation project");
        // Both the initial draft and the answer cycle go through the async
        // runtime; the spec generation endpoint itself stays synchronous
        // until the artifact cutover slice.
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "The clarified requirement");
        return project;
    }

    private SpecGenerationResponse generateSpec(Project project) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/specs/generate", project.id()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), SpecGenerationResponse.class);
    }

    @Test
    void generateSpecPersistsDerivedSnapshotWithProvenance() throws Exception {
        Project project = projectWithAnsweredLineage();

        mockMvc.perform(post("/api/v1/projects/{projectId}/specs/generate", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRun.status").value("completed"))
                .andExpect(jsonPath("$.agentRun.producedSpecSnapshotId").exists())
                .andExpect(jsonPath("$.specSnapshot.id").exists())
                .andExpect(jsonPath("$.specSnapshot.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.specSnapshot.routeId")
                        .value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.specSnapshot.createdByRunId").exists())
                .andExpect(jsonPath("$.specSnapshot.contextSnapshotId").exists())
                .andExpect(jsonPath("$.specSnapshot.sections", hasSize(2)))
                .andExpect(jsonPath("$.specSnapshot.sections[*].title",
                        org.hamcrest.Matchers.hasItems("Overview", "Open Questions")))
                .andExpect(jsonPath("$.specSnapshot.sourceRefs[0].kind").value("context"));
    }

    @Test
    void generatedSnapshotIsReadableThroughReadApiAndIsolationHolds() throws Exception {
        Project project = projectWithAnsweredLineage();

        SpecGenerationResponse first = generateSpec(project);
        SpecGenerationResponse second = generateSpec(project);

        // Second generation produces an independent snapshot, not a rewrite of
        // the first; the first snapshot is never treated as source truth.
        assertThat(second.specSnapshot().id()).isNotEqualTo(first.specSnapshot().id());
        assertThat(second.agentRun().producedSpecSnapshotId())
                .isEqualTo(second.specSnapshot().id());

        // Both are readable through the Phase 6.1 read API, scoped to the route.
        mockMvc.perform(get("/api/v1/specs/{snapshotId}", first.specSnapshot().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.specSnapshot().id().toString()))
                .andExpect(jsonPath("$.createdByRunId").value(first.agentRun().id().toString()));

        mockMvc.perform(get("/api/v1/projects/{projectId}/routes/{routeId}/specs",
                        project.id(), project.activeRouteId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void generateOnProjectWithoutTipNodeRejected() throws Exception {
        Project project = projectService.createProject("Spec empty project");
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId()).isNull();

        mockMvc.perform(post("/api/v1/projects/{projectId}/specs/generate", project.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_TIP_NODE"));
    }
}