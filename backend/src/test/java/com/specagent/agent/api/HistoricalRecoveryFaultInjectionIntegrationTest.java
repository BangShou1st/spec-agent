package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one product state the historical-recovery UX exists for — "answer
 * persisted, its STATE_UPDATE checkpoint missing" — is unreachable through the
 * public surface by construction (the DRAFT path refuses to cross an
 * unprocessed answer). A browser regression therefore has to declare exactly
 * one failure to reach it, via
 * {@link com.specagent.agent.decision.DeterministicEngineFaultPlan}.
 *
 * <p>This test proves that mechanism end to end through the real HTTP entry
 * points before any browser test relies on it: the declared failure lands on
 * the marked answer's cycle only, the persisted Answer and the failed-run
 * record survive it, the artifact gate refuses with a bounded recovery
 * identity, and the formal RESUME_ANSWER entry point then completes the
 * checkpoint — after which generation succeeds against the real state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HistoricalRecoveryFaultInjectionIntegrationTest {

    private static final String DIRECTIVE = "[[fail-state-update:2]]";

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
    private AgentRunEventService eventService;
    @Autowired
    private RunWorker worker;

    private UUID startRun(UUID projectId, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("runId").asText());
    }

    private List<Map<String, Object>> failedEvents(UUID runId) {
        return eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .map(AgentRunEvent::payload)
                .toList();
    }

    @Test
    void declaredFailureIsRecoverableThroughTheFormalEntryPoint() throws Exception {
        Project project = projectService.createProject("Fault plan project " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "会议时长要求是什么？", null, List.of(), true);
        UUID questionNodeId = root.id();

        // ── 1. The answer cycle fails on purpose, for this node only ──
        UUID answerRunId = startRun(project.id(),
                "{\"operation\": \"ANSWER_TIP\", \"freeText\": \"会议不超过45分钟。" + DIRECTIVE + "\"}",
                202);
        AgentRun claimed = runService.claimAnswerCycleRun(answerRunId).orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DETERMINISTIC_ENGINE_FAULT");

        // The user's answer survives its failed processing, and no checkpoint
        // was written — exactly the state the recovery flow exists for.
        Answer answer = answerService.findAnswerForNode(routeId, questionNodeId).orElseThrow();
        assertThat(answer.freeText()).contains("会议不超过45分钟");
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(failedEvents(answerRunId)).isNotEmpty();
        assertThat(failedEvents(answerRunId).get(0))
                .containsEntry("reason", "IllegalStateException");

        // ── 2. The artifact gate refuses with a bounded recovery identity ──
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").value(answer.id().toString()))
                .andExpect(jsonPath("$.details.routeId").value(routeId.toString()))
                .andExpect(jsonPath("$.details.nodeId").value(questionNodeId.toString()));
        assertThat(specSnapshotService.listByRoute(routeId)).isEmpty();

        String resumeBody = "{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                + answer.id() + "\", \"nodeId\": \"" + questionNodeId
                + "\", \"sourceRouteId\": \"" + routeId + "\"}";

        // ── 3. First recovery attempt is still inside the declared budget ──
        UUID firstRecovery = startRun(project.id(), resumeBody, 202);
        assertThatThrownBy(() -> worker.executeRun(
                runService.claimAnswerCycleRun(firstRecovery).orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(questionNodeId)))
                .as("a failed recovery never creates a second Answer")
                .hasSize(1);

        // ── 4. Retry: the ordinary runtime completes the checkpoint ──
        UUID secondRecovery = startRun(project.id(), resumeBody, 202);
        worker.executeRun(runService.claimAnswerCycleRun(secondRecovery).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(answer.id()))
                .as("recovery writes the missing STATE_UPDATE checkpoint")
                .isPresent();
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(questionNodeId)))
                .hasSize(1);
        assertThat(eventService.findByRunId(secondRecovery).stream()
                .map(AgentRunEvent::eventType))
                .contains("RUN_COMPLETED")
                .doesNotContain("HISTORICAL_ANSWER_RECOVERED");

        // ── 5. The gate is open: generation succeeds against the real state ──
        UUID artifactRun = startRun(project.id(),
                "{\"operation\": \"GENERATE_ARTIFACT\"}", 202);
        worker.executeRun(runService.claimArtifactRun(artifactRun).orElseThrow());
        assertThat(specSnapshotService.listByRoute(routeId)).hasSize(1);
    }

    @Test
    void ordinaryAnswersAreUnaffectedByTheFaultPlan() throws Exception {
        Project project = projectService.createProject("No directive project " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        UUID questionNodeId = nodeService.createRootNode(project.id(), routeId,
                "会议时长要求是什么？", null, List.of(), true).id();

        UUID answerRunId = startRun(project.id(),
                "{\"operation\": \"ANSWER_TIP\", \"freeText\": \"会议不超过45分钟。\"}", 202);
        worker.executeRun(runService.claimAnswerCycleRun(answerRunId).orElseThrow());

        Answer answer = answerService.findAnswerForNode(routeId, questionNodeId).orElseThrow();
        assertThat(answerPatchService.findBySourceAnswerId(answer.id()))
                .as("without the directive the answer cycle behaves exactly as before")
                .isPresent();
        assertThat(failedEvents(answerRunId)).isEmpty();
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId())
                .as("an ordinary answer still advances the route tip")
                .isNotEqualTo(questionNodeId);
    }
}
