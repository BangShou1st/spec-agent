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
 * Recovery of an unfinished answer cycle must reuse the persisted Answer and
 * its checkpoint — and must never accept a *different* submission by silently
 * replaying the old one.
 *
 * <p>Regression for the acceptance-case loss boundary: a tip whose answer was
 * already persisted was rewritten into a resume of that old answer, and the
 * newly submitted content was discarded without a trace, so the constraint the
 * user typed never reached the graph or the generated spec.
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

        // Nothing was overwritten, and no run was queued that could have replayed
        // the old answer while dropping the new text.
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
        // P1-A: answerId is a complete identity on its own; omitting the
        // optional nodeId must not bypass the content guard, or the worker
        // silently replays the persisted answer while dropping the new text.
        Project project = projectService.createProject("Guard by answer id");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RESUME_ANSWER", null, null, "edited answer", persisted.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CONTENT_MISMATCH"));

        // Nothing was overwritten and no run was queued to replay the old answer.
        assertSingleAnswer(project.activeRouteId(), root.id(), "original answer");
        assertThat(runService.claimNextAnswerCycle()).isEmpty();
    }

    @Test
    void resumeByIdWithoutNodeIdAcceptsTheIdenticalResubmission() throws Exception {
        // The same request shape without differing content stays a legitimate
        // resume: the identity is unambiguous, the content matches, so the
        // checkpoint resume proceeds without creating a second answer.
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
        // A pure recovery (no content at all) by answer id alone remains the
        // supported recovery entry.
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
        // An answerId that belongs to a different route must be refused on its
        // own merits — not silently resolved against the active route's tip.
        Project project = projectService.createProject("Guard cross-route id");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        Answer persisted = persistedFreeTextAnswer(project, root, "original answer");
        // The fork moves the project's active pointer to the branch; the answer
        // stays owned by the source route.
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
        // Naming an answerId that lives on node X while pointing nodeId at node
        // Y is an inconsistent identity: refuse instead of guessing.
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
