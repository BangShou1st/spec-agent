package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.loop.ContinuationCoordinator;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Background executor for queued runs. Dispatches by trigger type:
 * <ul>
 *   <li>DECISION_CYCLE → DecisionCycleService: single DECISION question draft
 *       with policy + execution.</li>
 *   <li>ANSWER_CYCLE → AnswerCycleService: 2-call convergence with policy +
 *       execution.</li>
 *   <li>GENERATE_SPEC → ArtifactCycleService: single ARTIFACT_GENERATION call
 *       with preserved grounding gates.</li>
 *   <li>REGENERATE_NODE → ReplacementCycleService: single DECISION content +
 *       deterministic topology commit.</li>
 * </ul>
 */
@Component
public class RunWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RunWorker.class);

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final AgentRunEventService eventService;
    private final AnswerCycleService answerCycleService;
    private final DecisionCycleService decisionCycleService;
    private final ArtifactCycleService artifactCycleService;
    private final ReplacementCycleService replacementCycleService;
    private final NodeQueryService nodeQueryService;
    private final ContinuationCycleService continuationCycleService;
    private final ContinuationCoordinator continuationCoordinator;

    public RunWorker(RunService runService,
                            AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            AgentRunEventService eventService,
                            AnswerCycleService answerCycleService,
                            DecisionCycleService decisionCycleService,
                            ArtifactCycleService artifactCycleService,
                            ReplacementCycleService replacementCycleService,
                            NodeQueryService nodeQueryService,
                            ContinuationCycleService continuationCycleService,
                            ContinuationCoordinator continuationCoordinator) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.eventService = eventService;
        this.answerCycleService = answerCycleService;
        this.decisionCycleService = decisionCycleService;
        this.artifactCycleService = artifactCycleService;
        this.replacementCycleService = replacementCycleService;
        this.nodeQueryService = nodeQueryService;
        this.continuationCycleService = continuationCycleService;
        this.continuationCoordinator = continuationCoordinator;
    }

    /** Claims and executes at most one queued run from each queue. */
    public void tryClaimAndExecute() {
        // Try DECISION_CYCLE first, then ANSWER_CYCLE, then NODE_QUERY.
        runService.claimNext().ifPresent(this::executeRun);
        runService.claimNextAnswerCycle().ifPresent(this::executeRun);
        runService.claimNextArtifact().ifPresent(this::executeRun);
        runService.claimNextRegenerate().ifPresent(this::executeRun);
        runService.claimNextNodeQuery().ifPresent(this::executeRun);
        runService.claimNextContinue().ifPresent(this::executeRun);
    }

    /**
     * Dispatches a claimed run to the appropriate handler based on trigger type.
     */
    public void executeRun(AgentRun run) {
        switch (run.triggerType()) {
            case ANSWER_CYCLE -> executeAnswerCycle(run);
            case NODE_QUERY -> executeNodeQuery(run);
            case GENERATE_SPEC -> executeArtifactGeneration(run);
            case REGENERATE_NODE -> executeRegenerate(run);
            case CONTINUE_CYCLE -> executeContinuationCycle(run);
            default -> executeDecisionCycle(run);
        }
    }

    /**
     * Spec snapshot generation: one ARTIFACT_GENERATION call plus the
     * preserved grounding gates in {@link ArtifactCycleService}.
     */
    private void executeArtifactGeneration(AgentRun run) {
        UUID runId = run.id();
        try {
            artifactCycleService.generateSpec(run);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Replacement: one DECISION for the content, deterministic topology
     * commit in {@link ReplacementCycleService}.
     */
    private void executeRegenerate(AgentRun run) {
        UUID runId = run.id();
        try {
            Map<String, Object> input = readRunInput(runId);
            UUID sourceRouteId = input.containsKey("routeId")
                    ? UUID.fromString((String) input.get("routeId")) : run.routeId();
            UUID targetNodeId = input.containsKey("nodeId")
                    ? UUID.fromString((String) input.get("nodeId")) : run.inputNodeId();
            String instruction = (String) input.get("freeText");
            if (sourceRouteId == null || targetNodeId == null) {
                throw new IllegalStateException(
                        "Replacement run is missing input parameters");
            }
            replacementCycleService.regenerate(
                    run, run.projectId(), sourceRouteId, targetNodeId, instruction);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Question draft: single DECISION plus policy/execution in
     * {@link DecisionCycleService}.
     */
    private void executeDecisionCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            decisionCycleService.draftQuestion(run);
            evaluateContinuationAfterTerminal(runId);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Answer cycle: 2-call convergence with policy + execution.
     * Reads input parameters from the persisted run event payload.
     */
    private void executeAnswerCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            // Read input parameters from the RUN_CREATED event payload.
            Map<String, Object> input = readRunInput(runId);
            String operation = run.operation() != null ? run.operation() : "ANSWER_TIP";
            UUID selectedOptionId = input.containsKey("selectedOptionId")
                    ? UUID.fromString((String) input.get("selectedOptionId")) : null;
            String freeText = (String) input.get("freeText");
            UUID answerId = input.containsKey("answerId")
                    ? UUID.fromString((String) input.get("answerId")) : null;
            AgentEvent.PersistenceIntent persistenceIntent = input.containsKey("persistenceIntent")
                    ? AgentEvent.PersistenceIntent.valueOf((String) input.get("persistenceIntent"))
                    : null;

            if ("RESUME_ANSWER".equals(operation) && answerId != null) {
                answerCycleService.resumeAnswer(run, run.projectId(), answerId, persistenceIntent);
            } else {
                answerCycleService.submitAnswer(run, run.projectId(), selectedOptionId, freeText,
                        persistenceIntent);
            }
            evaluateContinuationAfterTerminal(runId);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Autonomous continuation: one fresh DECISION in
     * {@link ContinuationCycleService}.
     */
    private void executeContinuationCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            continuationCycleService.executeContinuation(run);
            evaluateContinuationAfterTerminal(runId);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Single terminal continuation hook for every cycle that may legally
     * continue (decision, answer, continuation). The coordinator re-reads
     * the run from durable state, so it only ever sees committed terminal
     * facts: inside a managed transaction evaluation waits for afterCommit,
     * otherwise the preceding terminal writes already committed and the
     * evaluation runs inline. No cycle service calls the coordinator
     * itself, and no execution result, policy verdict, or model observation
     * is passed — the input stays one run id.
     */
    private void evaluateContinuationAfterTerminal(UUID runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            continuationCoordinator.continueIfEligible(runId);
                        }
                    });
        } else {
            continuationCoordinator.continueIfEligible(runId);
        }
    }

    /**
     * Node query: one DECISION call answering a contextual question about a
     * node; read-only by construction (mutations become pending proposals).
     */
    private void executeNodeQuery(AgentRun run) {
        UUID runId = run.id();
        try {
            Map<String, Object> input = readRunInput(runId);
            // routeId is OPTIONAL: floating nodes query with a null route and
            // the anchor node as the sole context.
            Object routeRaw = input.get("routeId");
            UUID routeId = routeRaw == null || String.valueOf(routeRaw).isBlank()
                    ? run.routeId() : UUID.fromString((String) routeRaw);
            UUID nodeId = input.containsKey("nodeId")
                    ? UUID.fromString((String) input.get("nodeId")) : run.inputNodeId();
            String question = (String) input.get("question");
            if (nodeId == null || question == null) {
                throw new IllegalStateException("Node query run is missing input parameters");
            }
            nodeQueryService.executeNodeQuery(run, routeId, nodeId, question);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * Reads the input parameters from the RUN_CREATED event for this run.
     */
    private Map<String, Object> readRunInput(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType()))
                .map(AgentRunEvent::payload)
                .findFirst()
                .orElse(Map.of());
    }

    private void failIfNotTerminal(UUID runId, RuntimeException ex) {
        String step = failureStepFor(ex);
        LOG.warn("Agent run {} failed: {}", runId, step);
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, "failed:" + step);
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED", Map.of("reason", step));
        }
    }

    private String failureStepFor(RuntimeException ex) {
        if (ex instanceof AgentBrainUnavailableException) {
            return "brain_unavailable";
        }
        return ex.getClass().getSimpleName();
    }
}
