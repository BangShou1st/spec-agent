package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunFailureReasons;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import com.specagent.spec.SpecSnapshotService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Artifact generation must never publish a document that silently omits an
 * answer the user already gave.
 *
 * <p>A route tip carrying a persisted Answer with no AnswerPatch never finished
 * its STATE_UPDATE, so that answer's claims are missing from the state a spec
 * would be derived from. Both the command surface and the queued run refuse it,
 * and the refusal names the recovery the checkpoint already supports.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ArtifactGenerationAnswerGateIntegrationTest {

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
    private AgentRunService agentRunService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private RunWorker worker;

    private Project projectWithTipQuestion() {
        Project project = projectService.createProject("Artifact gate project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        return project;
    }

    private Answer persistedUnprocessedAnswer(Project project) {
        return answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId(),
                null, "saved answer", "user");
    }

    @Test
    void generationIsRejectedWhileTheTipAnswerHasNoCheckpoint() throws Exception {
        Project project = projectWithTipQuestion();
        persistedUnprocessedAnswer(project);

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"));

        // Nothing was generated and nothing was queued that could generate it.
        assertThat(runService.claimNextArtifact()).isEmpty();
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
    }

    @Test
    void generationProceedsOnceTheAnswerHasItsCheckpoint() throws Exception {
        Project project = projectWithTipQuestion();
        Answer answer = persistedUnprocessedAnswer(project);
        answerPatchService.save(project.id(), project.activeRouteId(), answer.nodeId(),
                answer.id(), List.of(new Claim(null, ClaimKind.fromCode("goal"),
                        "The goal is clear.", ClaimStatus.fromCode("confirmed"), 0.9,
                        answer.nodeId(), answer.id())), null);

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted());

        assertThat(runService.claimNextArtifact()).isPresent();
    }

    @Test
    void queuedGenerationFailsClosedWhenTheTipAnswerIsNeverProcessed() throws Exception {
        Project project = projectWithTipQuestion();
        UUID tipNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        MvcResult accepted = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accepted.getResponse().getContentAsString()).get("runId").asText();

        // The user answers before the queued run is claimed; the answer's
        // STATE_UPDATE never completes.
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), tipNodeId,
                null, "saved answer", "user");

        AgentRun claimed = runService.claimNextArtifact().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(RuntimeException.class);

        // The durable FAILED status is written in its own REQUIRES_NEW
        // transaction (invisible to this test's transaction); the non-
        // transactional TypedRunFailureIntegrationTest asserts that status. Here
        // the decisive evidence is the recorded failure itself.
        AgentRun run = agentRunService.getRun(UUID.fromString(runId)).orElseThrow();
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();

        AgentRunEvent failed = eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .findFirst().orElseThrow();
        assertThat(failed.payload())
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE)
                .containsEntry("errorCode", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
        assertThat((String) failed.payload().get("summary")).isNotBlank();

        // The same explanation reaches the client through the whitelisted
        // progress read model.
        mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        project.id(), run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.summary").value(failed.payload().get("summary")));
    }
}
