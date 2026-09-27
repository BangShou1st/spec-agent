package com.specagent.workspace.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:SpecGenerateApiIntegrationTest.java
 *
 * 测试目标:规格生成切换的集成测试——{@code POST /agent-runs} 且
 * {@code operation=GENERATE_ARTIFACT} 时返回 202 + runId,worker 执行一次
 * ARTIFACT_GENERATION 调用,派生快照连同其 run 溯源一起持久化,并通过
 * Phase 6.1 的 spec 读取 API 暴露。真实性保证(fails-closed grounding)
 * 仍然有效(每个 section 必须引用允许的来源引用)。
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
    private com.specagent.agent.runtime.AgentRunService agentRunService;

    /** 通过生产路径起草一个根问题并回答它。 */
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

        // 命令在任何模型工作发生之前就已返回 202。
        assertThat(agentRunStatus(runId)).isEqualTo("created");

        executeQueued(runId);

        assertThat(agentRunStatus(runId)).isEqualTo("completed");

        // 生成的快照可连同其溯源、有真实依据的 section 与未决事项,
        // 一起通过 spec 读取 API 读取。
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

        // 每次生成产出独立的快照;任何一个都不会被当作另一个的事实来源。
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

    private com.specagent.agent.runtime.AgentRun runById(String runId) {
        return agentRunService.getRun(UUID.fromString(runId)).orElseThrow();
    }
}
