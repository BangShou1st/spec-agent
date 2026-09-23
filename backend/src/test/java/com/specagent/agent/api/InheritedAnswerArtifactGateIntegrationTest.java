package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunFailureReasons;
import com.specagent.agent.runtime.IncompleteAnswerCycleException;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.agent.snapshot.LegacyFrozenInputUnavailableException;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The spec gates must see the answers the spec context actually uses — the
 * route's own answers PLUS the legal inherited prefix — not merely the route's
 * own tip answer.
 *
 * <p>P1-B regression: forking from an answered node whose STATE_UPDATE never
 * completed inherits that answer into the branch context while both gates
 * (enqueue and pre-execution) looked only at the branch's own tip answer, so
 * the branch could generate a spec that reads as complete while silently
 * omitting the user's inherited answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InheritedAnswerArtifactGateIntegrationTest {

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
    @Autowired
    private RouteHistoryResolver routeHistoryResolver;
    @Autowired
    private ContextBuilder contextBuilder;

    /** Route with one root question answered, its STATE_UPDATE never run. */
    private Project projectWithAnsweredRoot() {
        Project project = projectService.createProject("Inherited gate project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), root.id(),
                null, "goal answer", "user");
        return project;
    }

    private Route forkFromRoot(Project project) {
        UUID rootId = routeService.getRoute(project.activeRouteId()).orElseThrow().rootNodeId();
        return routeService.forkFromNode(project.id(), project.activeRouteId(), rootId,
                "branch route");
    }

    /** Gives every effective answer of the route a checkpoint patch. */
    private void processAllEffectiveAnswers(UUID routeId) {
        Route route = routeService.getRoute(routeId).orElseThrow();
        List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
        for (Answer answer : routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage)) {
            answerPatchService.save(answer.projectId(), answer.routeId(), answer.nodeId(),
                    answer.id(), List.of(new Claim(null, ClaimKind.fromCode("goal"),
                            "processed claim for " + answer.id(), ClaimStatus.fromCode("confirmed"),
                            0.9, answer.nodeId(), answer.id())), null);
        }
    }

    private List<Map<String, Object>> failedEvents(UUID runId) {
        return eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .map(AgentRunEvent::payload)
                .toList();
    }

    @Test
    void draftCannotAdvancePastAnUnprocessedAnswer() throws Exception {
        Project project = projectWithAnsweredRoot();
        UUID routeId = project.activeRouteId();
        UUID rootId = routeService.getRoute(routeId).orElseThrow().tipNodeId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"DRAFT_QUESTION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").isNotEmpty())
                .andExpect(jsonPath("$.details.routeId").value(routeId.toString()))
                .andExpect(jsonPath("$.details.nodeId").value(rootId.toString()));

        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(rootId);
        assertThat(runService.claimNext()).isEmpty();
    }

    @Test
    void queuedDraftFailsClosedIfTheAnswerBecomesUnprocessedBeforeExecution() {
        Project project = projectService.createProject("Queued draft race " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "What is required?", null, List.of(), true);
        AgentRun queued = runService.createQueuedDraftQuestion(project.id());
        answerService.finalizeAnswer(project.id(), routeId, root.id(), null,
                "saved before claim", "user");

        AgentRun claimed = runService.claimDecisionCycleRun(queued.id()).orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IncompleteAnswerCycleException.class);

        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(root.id());
        assertThat(failedEvents(queued.id())).anySatisfy(payload ->
                assertThat(payload).containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE));
    }

    @Test
    void enqueueIsRejectedWhileTheInheritedAnswerIsUnprocessed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);

        // The branch's own tip is clean (no route-local answer), yet the spec
        // context inherits the unprocessed answer through the fork point.
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"));

        assertThat(runService.claimNextArtifact()).isEmpty();
        assertThat(specSnapshotService.listByRoute(fork.id())).isEmpty();
    }

    @Test
    void queuedGenerationFailsClosedOnTheInheritedUnprocessedAnswer() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);

        // The enqueue gate now correctly refuses this state (409); to exercise
        // the executor gate directly, queue the run through RunService as if it
        // had been enqueued before the inherited answer became unprocessed.
        AgentRun run = runService.createQueuedArtifactGeneration(
                project.id(), null, "gate", fork.id());

        AgentRun claimed = runService.claimNextArtifact().orElseThrow();
        assertThat(claimed.routeId()).isEqualTo(fork.id());
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(RuntimeException.class);

        assertThat(specSnapshotService.listByRoute(fork.id())).isEmpty();
        List<Map<String, Object>> failures = failedEvents(run.id());
        assertThat(failures).isNotEmpty();
        assertThat(failures.get(0))
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
    }

    @Test
    void unprocessedAnswerThatIsNoLongerTheTipIsStillCaughtByTheEffectiveHistory() throws Exception {
        // Historical compatibility fixture: create the already-invalid state
        // directly. The fixed DRAFT path must never create this state; this
        // fixture preserves coverage for rows written by the old path.
        Project project = projectWithAnsweredRoot();
        Route source = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Node historicalTip = nodeService.createChildNode(project.id(), source.id(), rootId,
                "Legacy later question?", null, List.of(), true);
        assertThat(historicalTip.id()).isEqualTo(
                routeService.getRoute(source.id()).orElseThrow().tipNodeId());

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.details.answerId").isNotEmpty())
                .andExpect(jsonPath("$.details.routeId").value(source.id().toString()))
                .andExpect(jsonPath("$.details.nodeId").value(rootId.toString()));
    }

    @Test
    void nonTipUnprocessedAnswerCanBeRecoveredThroughTheFormalEntryPoint() throws Exception {
        Project project = projectService.createProject("Historical recovery project " + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "What must be preserved?", null, List.of(), true);

        // Compatibility fixture with the original pre-answer ContextSnapshot
        // attached to the producing run. The answer is then made non-tip by a
        // legacy direct node write, matching the state created by the old bug.
        AgentRun original = runService.createQueuedRunWithInputResult(
                project.id(), "ANSWER_TIP", root.id(), null, null, null, null);
        var originalSnapshot = contextBuilder.buildForRoute(
                project.id(), routeId, root.id(), original.id(), ContextOperationType.NORMAL);
        agentRunService.attachContext(original.id(), originalSnapshot.id(), "legacy_fixture_context");
        Answer answer = answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "preserve the constraint", "user");
        agentRunService.markPersistedAnswer(original.id(), answer.id(), "legacy_fixture_answer");
        agentRunService.complete(original.id(), AgentRunStatus.FAILED, "legacy_fixture_incomplete");
        Node later = nodeService.createChildNode(project.id(), routeId, root.id(),
                "Legacy later question?", null, List.of(), true);

        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + answer.id() + "\", \"nodeId\": \"" + root.id()
                                        + "\", \"sourceRouteId\": \"" + routeId + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(recoveryRunId).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).hasValueSatisfying(patch -> {
            assertThat(patch.routeId()).isEqualTo(routeId);
            assertThat(patch.sourceNodeId()).isEqualTo(root.id());
            assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
            assertThat(patch.claims()).isNotEmpty();
            assertThat(patch.claims()).allSatisfy(claim -> {
                if (claim.sourceAnswerId() != null) {
                    assertThat(claim.sourceAnswerId()).isEqualTo(answer.id());
                }
                if (claim.sourceNodeId() != null) {
                    assertThat(claim.sourceNodeId()).isEqualTo(root.id());
                }
            });
        });
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId()).isEqualTo(later.id());
        assertThat(answerService.findAnswersForRouteAndNodeIds(routeId, List.of(root.id())))
                .hasSize(1);
        assertThat(eventService.findByRunId(recoveryRunId).stream()
                .map(AgentRunEvent::eventType))
                .contains("HISTORICAL_ANSWER_RECOVERED")
                .doesNotContain("DECISION_STARTED");
    }

    @Test
    void historicalRecoveryWithoutOriginalSnapshotFailsClosed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route source = routeService.getRoute(project.activeRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Answer answer = answerService.findAnswerForNode(source.id(), rootId).orElseThrow();
        nodeService.createChildNode(project.id(), source.id(), rootId,
                "Legacy later question without snapshot?", null, List.of(), true);

        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + answer.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());

        assertThatThrownBy(() -> worker.executeRun(
                runService.claimAnswerCycleRun(recoveryRunId).orElseThrow()))
                .isInstanceOf(LegacyFrozenInputUnavailableException.class)
                .hasMessageContaining("LEGACY_FROZEN_INPUT_UNAVAILABLE");
        assertThat(answerPatchService.findBySourceAnswerId(answer.id())).isEmpty();
        assertThat(routeService.getRoute(source.id()).orElseThrow().tipNodeId())
                .isNotEqualTo(rootId);
    }

    @Test
    void branchCanGenerateOnceEveryInheritedAnswerIsProcessed() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);
        Route source = routeService.getRoute(fork.sourceRouteId()).orElseThrow();
        UUID rootId = source.rootNodeId();
        Answer inherited = answerService.findAnswerForNode(source.id(), rootId).orElseThrow();

        // Use the formal recovery entry on the owning route. This is not a
        // direct AnswerPatch write: the runtime reuses the immutable answer,
        // completes its checkpoint, and keeps the branch's tip untouched.
        MvcResult recovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + inherited.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID recoveryRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(recovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(recoveryRunId).orElseThrow());

        assertThat(answerPatchService.findBySourceAnswerId(inherited.id())).isPresent();
        assertThat(answerService.findAnswersForRouteAndNodeIds(source.id(), List.of(rootId)))
                .hasSize(1);
        UUID sourceTipAfterRecovery = routeService.getRoute(source.id()).orElseThrow().tipNodeId();
        assertThat(routeService.getRoute(fork.id()).orElseThrow().tipNodeId())
                .isEqualTo(rootId);

        // A second click is a historical, already-checkpointed recovery. It
        // must be a durable no-op: no second STATE_UPDATE, Answer, Patch, or
        // graph mutation.
        MvcResult repeatedRecovery = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"RESUME_ANSWER\", \"answerId\": \""
                                        + inherited.id() + "\", \"nodeId\": \"" + rootId
                                        + "\", \"sourceRouteId\": \"" + source.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID repeatedRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(repeatedRecovery.getResponse().getContentAsString())
                        .get("runId").asText());
        worker.executeRun(runService.claimAnswerCycleRun(repeatedRunId).orElseThrow());
        assertThat(routeService.getRoute(source.id()).orElseThrow().tipNodeId())
                .isEqualTo(sourceTipAfterRecovery);
        assertThat(answerService.findAnswersForRouteAndNodeIds(source.id(), List.of(rootId)))
                .hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(inherited.id())).hasValueSatisfying(patch ->
                assertThat(patch.id()).isNotNull());
        assertThat(eventService.findByRunId(repeatedRunId).stream()
                .map(AgentRunEvent::eventType))
                .contains("STATE_UPDATE_SKIPPED")
                .doesNotContain("DECISION_STARTED");

        // The branch now passes the effective-history gate and its own
        // artifact run can be claimed by id; no shared queue draining.
        MvcResult artifact = mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\", \"sourceRouteId\": \""
                                + fork.id() + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID artifactRunId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(artifact.getResponse().getContentAsString())
                        .get("runId").asText());
        AgentRun claimedArtifact = runService.claimArtifactRun(artifactRunId).orElseThrow();
        worker.executeRun(claimedArtifact);
        assertThat(specSnapshotService.listByRoute(fork.id())).hasSize(1);
    }

    @Test
    void unrelatedSiblingRouteWithUnprocessedAnswerDoesNotBlock() throws Exception {
        Project project = projectWithAnsweredRoot();
        Route fork = forkFromRoot(project);
        processAllEffectiveAnswers(fork.id());

        // A second branch of the same project keeps an unprocessed tip answer;
        // it shares no lineage material with the fork beyond the processed
        // root, and must not block the fork's generation.
        Route source = routeService.getRoute(fork.sourceRouteId()).orElseThrow();
        Node siblingTip = nodeService.createChildNode(project.id(), source.id(), source.tipNodeId(),
                "Sibling question?", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), source.id(), siblingTip.id(),
                null, "sibling answer", "user");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\": \"GENERATE_ARTIFACT\"}"))
                .andExpect(status().isAccepted());

        assertThat(runService.claimNextArtifact()).isPresent();
    }
}
