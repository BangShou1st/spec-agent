package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.SideEffectClass;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.ProposedClaim;
import com.specagent.agent.contract.StateUpdateResult;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repair/resume semantic replay guarantee: when an answer cycle fails after
 * the Answer (and optionally its patch checkpoint) was persisted, the retry
 — routed through {@code RESUME_ANSWER} — must rebuild the DECISION/STATE_UPDATE
 * inputs from the immutable persisted Answer. The second attempt's triggering
 * event must be semantically identical to the first user submission
 * (ANSWER_SUBMITTED + selectedOptionId + freeText + source node), never a
 * context-free CONTINUE, and retry must never create a second Answer or a
 * second patch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnswerResumeSemanticReplayIntegrationTest {

    @TestConfiguration
    static class ScriptedEngineConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        AgentDecisionEngine scriptedDecisionEngine() {
            return new ScriptedDecisionEngine();
        }

        @Bean
        CapabilityAdapter resumeDriftCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.resume-drift", "1",
                            "resume drift probe", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("marker", invocation.invocationKey()),
                            List.of(), Map.of(), List.of());
                }
            };
        }
    }

    /**
     * Deterministic engine that records every envelope it receives and can
     * be told to fail the Nth STATE_UPDATE or DECISION call.
     */
    static class ScriptedDecisionEngine implements AgentDecisionEngine {

        final List<AgentRequestEnvelope> stateUpdates = new ArrayList<>();
        final List<AgentRequestEnvelope> decisions = new ArrayList<>();
        int failStateUpdateAt = -1;
        int failDecisionAt = -1;

        @Override
        public com.specagent.agent.contract.AgentArtifactResponse runArtifactGeneration(
                com.specagent.agent.contract.AgentRequestEnvelope request) {
            throw new UnsupportedOperationException("not scripted for artifact generation");
        }

        @Override
        public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
            stateUpdates.add(request);
            if (stateUpdates.size() == failStateUpdateAt) {
                throw new AgentBrainUnavailableException("scripted STATE_UPDATE failure",
                        new IllegalStateException("scripted"));
            }
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    new StateUpdateResult(List.of(new ProposedClaim(
                            "goal", "The user clarified the outcome.", "confirmed",
                            0.9, List.of()))),
                    null, null,
                    new UsageView(1, List.of()),
                    Map.of());
        }

        @Override
        public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
            decisions.add(request);
            if (decisions.size() == failDecisionAt) {
                throw new AgentBrainUnavailableException("scripted DECISION failure",
                        new IllegalStateException("scripted"));
            }
            UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    null,
                    new ObservationView(List.of("known"), List.of(), List.of(), List.of()),
                    new ActionProposal(
                            "REQUEST_USER_INPUT",
                            Map.of(
                                    "questionText", "What is the most important outcome?",
                                    "options", List.of(Map.of("label", "Clarify")),
                                    "allowFreeAnswer", true),
                            snapshotId,
                            request.snapshot().contextHash(),
                            List.of(),
                            UUID.randomUUID(),
                            request.runId().toString(),
                            List.of()),
                    new UsageView(1, List.of()),
                    Map.of());
        }
    }

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ScriptedDecisionEngine scriptedEngine;
    @Autowired private CapabilityRuntime capabilityRuntime;

    private Project project;
    private Route route;
    private Node rootNode;
    private UUID selectedOptionId;
    private String freeText;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("回答恢复语义测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootNode = nodeService.createRootNode(project.id(), route.id(),
                "最重要的目标是什么？", null, List.of(), true);
        // Free-text-only submission: the node owns its options, and a random
        // option id would be rejected before any Answer is persisted.
        selectedOptionId = null;
        freeText = "聚焦离线同步的冲突处理";
        scriptedEngine.stateUpdates.clear();
        scriptedEngine.decisions.clear();
        scriptedEngine.failStateUpdateAt = -1;
        scriptedEngine.failDecisionAt = -1;
    }

    @Test
    void resumeAfterDecisionFailureReplaysOriginalSubmissionSemantics() {
        // 1. Submit with option X + free text Y; STATE_UPDATE succeeds (patch
        //    persisted), first DECISION fails.
        scriptedEngine.failDecisionAt = 1;
        UUID firstRunId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        // The safe checkpoints survived: exactly one Answer, exactly one patch.
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        UUID answerId = answers.get(0).id();
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();

        // 2. Retry routes to RESUME_ANSWER with the persisted answer id.
        UUID secondRunId = runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);
        assertThat(agentRunService.getRun(secondRunId).orElseThrow().status())
                .isEqualTo(com.specagent.agent.AgentRunStatus.COMPLETED);

        // 3. The resumed cycle reused the persisted patch instead of
        //    re-running STATE_UPDATE.
        assertThat(scriptedEngine.stateUpdates).as("STATE_UPDATE calls").hasSize(1);
        assertThat(eventService.findByRunId(secondRunId)).anySatisfy(event ->
                assertThat(event.eventType()).isEqualTo("STATE_UPDATE_SKIPPED"));

        // 4. Semantic equivalence of both DECISION envelopes.
        assertThat(scriptedEngine.decisions).hasSize(2);
        var firstEvent = scriptedEngine.decisions.get(0).event();
        var resumedEvent = scriptedEngine.decisions.get(1).event();
        assertThat(firstEvent.kind()).isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.kind())
                .as("resume must not degrade into a context-free CONTINUE")
                .isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(firstEvent.selectedOptionId());
        assertThat(resumedEvent.freeText()).isEqualTo(firstEvent.freeText());
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(firstEvent.anchorNodeId());
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(selectedOptionId);
        assertThat(resumedEvent.freeText()).isEqualTo(freeText);
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(rootNode.id());

        // 5. No duplicate durable artifacts after the retry.
        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();
        assertThat(allPatches()).hasSize(1);
    }

    @Test
    void resumeWithoutPersistedPatchRebuildsStateUpdateFromPersistedAnswer() {
        // First attempt fails during STATE_UPDATE: answer exists, no patch yet.
        scriptedEngine.failStateUpdateAt = 1;
        UUID firstRunId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answers.get(0).id())).isEmpty();

        // Resume must rebuild the STATE_UPDATE input from the persisted
        // Answer and still never create a second Answer.
        UUID answerId = answers.get(0).id();
        runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        assertThat(scriptedEngine.stateUpdates).hasSize(2);
        var firstEvent = scriptedEngine.stateUpdates.get(0).event();
        var resumedEvent = scriptedEngine.stateUpdates.get(1).event();
        assertThat(resumedEvent.kind()).isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(firstEvent.selectedOptionId());
        assertThat(resumedEvent.freeText()).isEqualTo(firstEvent.freeText());
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(rootNode.id());

        assertThat(scriptedEngine.decisions).hasSize(1);
        assertThat(scriptedEngine.decisions.get(0).event().kind())
                .isEqualTo("ANSWER_SUBMITTED");

        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(allPatches()).hasSize(1);
    }

    /**
     * T5 — repair with a live-workspace drift: after the first attempt's
     * DECISION failed (post-state snapshot + frozen projection already
     * durable), a capability result lands in the project. The RESUME_ANSWER
     * retry must skip STATE_UPDATE, create no second Answer, and give DECISION
     * the ORIGINAL frozen post-state model context — same snapshot id, same
     * capability observations — not a live rebuild over the drifted workspace.
     */
    @Test
    void resumeReplaysOriginalFrozenPostStateDecisionInputDespiteLiveDrift() {
        // 1. First attempt: STATE_UPDATE persists the patch, DECISION fails.
        scriptedEngine.failDecisionAt = 1;
        runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        assertThat(scriptedEngine.decisions).hasSize(1);
        var originalDecisionEnvelope = scriptedEngine.decisions.get(0);
        UUID answerId = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id())).get(0).id();

        // 2. Live drift after the failed attempt: a new capability result
        //    becomes visible to any live rebuild.
        capabilityRuntime.invoke("resume-drift-" + UUID.randomUUID(),
                "test.resume-drift", project.id(), null, Map.of());

        // 3. Repair through RESUME_ANSWER.
        runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        // 4. STATE_UPDATE was not rerun; DECISION ran again.
        assertThat(scriptedEngine.stateUpdates).as("no STATE_UPDATE rerun").hasSize(1);
        assertThat(scriptedEngine.decisions).hasSize(2);

        // 5. The resumed DECISION received the ORIGINAL frozen post-state
        //    model context: identical snapshot identity AND identical payload
        //    — the drifted capability observation is absent, exactly as in the
        //    first attempt.
        var resumedEnvelope = scriptedEngine.decisions.get(1);
        assertThat(resumedEnvelope.snapshot()).isEqualTo(originalDecisionEnvelope.snapshot());
        assertThat(resumedEnvelope.snapshot().capabilityResults())
                .as("post-freeze capability drift must not enter the replayed input")
                .isEqualTo(originalDecisionEnvelope.snapshot().capabilityResults());

        // 6. No second Answer, no second patch.
        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();
    }

    private List<AnswerPatch> allPatches() {
        return answerPatchService.findBySourceAnswerId(rootNodeIdAnswers().get(0).id())
                .map(List::of).orElseGet(List::of);
    }

    private List<Answer> rootNodeIdAnswers() {
        return answerService.findAnswersForRouteAndNodeIds(route.id(), List.of(rootNode.id()));
    }
}
