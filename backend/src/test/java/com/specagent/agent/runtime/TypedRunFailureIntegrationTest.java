package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.ProposedClaim;
import com.specagent.agent.contract.StateUpdateResult;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.BrainFailureCode;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failed answer cycle reports <em>which</em> cause it was and always leaves a
 * recoverable checkpoint.
 *
 * <p>Deterministic regression for the acceptance-run chain: the model output was
 * rejected by the brain contract, the run failed with one opaque reason, and the
 * only thing the retry could do was resume the same persisted answer. This test
 * pins that the cause is typed, that a deterministic contract failure is not
 * retried automatically, and that the resume path reuses the existing Answer and
 * patch without creating a second of either.
 */
@SpringBootTest
@ActiveProfiles("test")
// Deliberately NOT @Transactional: the durable run failure is recorded in its
// own REQUIRES_NEW transaction, exactly as in production, so a surrounding test
// transaction would hide the very record this suite asserts on.
class TypedRunFailureIntegrationTest {

    @TestConfiguration
    static class TypedFailureEngineConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TypedFailureEngine typedFailureEngine() {
            return new TypedFailureEngine();
        }
    }

    /** Deterministic engine that can fail DECISION with a typed brain code. */
    static class TypedFailureEngine implements AgentDecisionEngine {

        final AtomicInteger stateUpdates = new AtomicInteger();
        final AtomicInteger decisions = new AtomicInteger();
        volatile boolean failDecision = true;
        volatile BrainFailureCode decisionFailureCode = BrainFailureCode.MODEL_CONTRACT_VIOLATION;

        @Override
        public com.specagent.agent.contract.AgentArtifactResponse runArtifactGeneration(
                AgentRequestEnvelope request) {
            throw new UnsupportedOperationException("not scripted for artifact generation");
        }

        @Override
        public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
            stateUpdates.incrementAndGet();
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
            decisions.incrementAndGet();
            if (failDecision) {
                throw new AgentBrainUnavailableException(
                        "model output violates the DECISION contract",
                        new IllegalStateException("scripted contract violation"),
                        decisionFailureCode);
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
                                    "questionText", "What is the next most important outcome?",
                                    "options", List.of(Map.of("label", "Clarify the goal")),
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

    @Autowired
    private TypedFailureEngine engine;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private com.specagent.spec.SpecSnapshotService specSnapshotService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker runWorker;
    @Autowired
    private RouteService routeService;

    @BeforeEach
    void resetEngine() {
        engine.failDecision = true;
        engine.decisionFailureCode = BrainFailureCode.MODEL_CONTRACT_VIOLATION;
        engine.stateUpdates.set(0);
        engine.decisions.set(0);
    }

    private Map<String, Object> runFailure(AgentRun run) {
        return eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run never recorded RUN_FAILED"))
                .payload();
    }

    private record Fixture(Project project, Node root) {
    }

    private Fixture fixture(String title) {
        Project project = projectService.createProject(title);
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        return new Fixture(project, root);
    }

    /**
     * Enqueues an answer run and drives it through the worker, tolerating the
     * expected scripted failure so the durable run record can be inspected.
     */
    private AgentRun submitExpectingFailure(Project project, String freeText) {
        UUID tipNodeId = routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId();
        UUID runId = answerDriver.enqueueOnly(project.id(), "ANSWER_TIP", tipNodeId,
                null, freeText, null);
        try {
            runWorker.executeRun(runService.claimAnswerCycleRun(runId).orElseThrow());
        } catch (RuntimeException expected) {
            // RunWorker rethrows after recording the typed failure.
        }
        return runService.getRun(runId).orElseThrow();
    }

    @Test
    void contractFailureIsTypedAndTheRetryReusesTheSameCheckpoint() {
        Fixture fixture = fixture("Typed contract failure");
        Project project = fixture.project();
        Node root = fixture.root();

        AgentRun first = submitExpectingFailure(project, "the durable answer");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        // The brain failure happened after the STATE_UPDATE checkpoint landed.
        assertThat(engine.stateUpdates.get()).isEqualTo(1);
        // A deterministic contract failure is not retried automatically.
        assertThat(engine.decisions.get()).isEqualTo(1);

        Map<String, Object> failure = runFailure(first);
        assertThat(failure)
                .containsEntry("reason", "model_contract_violation")
                .containsEntry("errorCode", "model_contract_violation");
        assertThat((String) failure.get("summary")).isNotBlank();

        // The checkpoint is intact: exactly one answer and one patch.
        Answer persisted = answerService.findAnswerForNode(project.activeRouteId(), root.id())
                .orElseThrow();
        assertThat(answerPatchService.findBySourceAnswerId(persisted.id())).isPresent();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);

        // Recovery resumes the SAME answer and completes without duplicating
        // either durable record.
        engine.failDecision = false;
        AnswerCycleTestDriver.SubmittedAnswer repaired =
                answerDriver.resumeAnswer(project.id(), persisted.id());

        assertThat(repaired.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(repaired.run().producedAnswerId()).isEqualTo(persisted.id());
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(root.id()))).hasSize(1);
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);
        assertThat(engine.decisions.get()).isEqualTo(2);
    }

    @Test
    void queuedArtifactRunFailsClosedOnAnUnprocessedTipAnswer() {
        Fixture fixture = fixture("Queued artifact gate");
        Project project = fixture.project();
        UUID tipNodeId = routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId();

        // Queue the artifact run while the tip is still unanswered...
        UUID runId = runService.createQueuedArtifactGeneration(project.id(), null, "gate", null)
                .id();
        // ...then the user answers and that answer's STATE_UPDATE never lands.
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), tipNodeId,
                null, "saved answer", "user");

        try {
            runWorker.executeRun(runService.claimNextArtifact().orElseThrow());
        } catch (RuntimeException expected) {
            // The gate refuses before any model call.
        }

        AgentRun run = runService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(run))
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE)
                .containsEntry("errorCode", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
        // No artifact was derived from the incomplete state, and no model call
        // was made for it.
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
        assertThat(engine.decisions.get()).isZero();
    }

    @Test
    void timeoutIsReportedAsATimeoutNotAsAnUnavailableBrain() {
        Fixture fixture = fixture("Typed timeout failure");
        engine.decisionFailureCode = BrainFailureCode.BRAIN_TIMEOUT;

        AgentRun first = submitExpectingFailure(fixture.project(), "answer under timeout");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "brain_timeout")
                .containsEntry("errorCode", "brain_timeout");
    }

    @Test
    void ungroundedReferenceIsReportedAsABlockedCitation() {
        Fixture fixture = fixture("Typed ungrounded failure");
        engine.decisionFailureCode = BrainFailureCode.MODEL_UNGROUNDED_REFERENCE;

        AgentRun first = submitExpectingFailure(fixture.project(), "answer with foreign ref");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "model_ungrounded_reference")
                .containsEntry("errorCode", "model_ungrounded_reference");
    }

    @Test
    void providerFailureIsReportedAsAProviderFailure() {
        Fixture fixture = fixture("Typed provider failure");
        engine.decisionFailureCode = BrainFailureCode.MODEL_PROVIDER_FAILURE;

        AgentRun first = submitExpectingFailure(fixture.project(),
                "answer under provider failure");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "model_provider_failure")
                .containsEntry("errorCode", "model_provider_failure");
    }
}
