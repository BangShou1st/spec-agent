package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Background executor for queued runs. Dispatches by trigger type:
 * <ul>
 *   <li>DECISION_CYCLE → Stage A logic: STATE_UPDATE + DECISION, record proposal only.</li>
 *   <li>ANSWER_CYCLE → AnswerCycleService: 2-call convergence with policy + execution.</li>
 * </ul>
 */
@Component
public class RunWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RunWorker.class);

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AgentRunEventService eventService;
    private final AnswerCycleService answerCycleService;
    private final NodeQueryService nodeQueryService;

    public RunWorker(RunService runService,
                            AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            ContextBuilder contextBuilder,
                            ContextGuard contextGuard,
                            AgentInputSnapshotBuilder snapshotBuilder,
                            AgentDecisionEngine decisionEngine,
                            AgentRunEventService eventService,
                            AnswerCycleService answerCycleService,
                            NodeQueryService nodeQueryService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.eventService = eventService;
        this.answerCycleService = answerCycleService;
        this.nodeQueryService = nodeQueryService;
    }

    /** Claims and executes at most one queued run from each queue. */
    public void tryClaimAndExecute() {
        // Try DECISION_CYCLE first, then ANSWER_CYCLE, then NODE_QUERY.
        runService.claimNext().ifPresent(this::executeRun);
        runService.claimNextAnswerCycle().ifPresent(this::executeRun);
        runService.claimNextNodeQuery().ifPresent(this::executeRun);
    }

    /**
     * Dispatches a claimed run to the appropriate handler based on trigger type.
     */
    public void executeRun(AgentRun run) {
        if (run.triggerType() == AgentRunTriggerType.ANSWER_CYCLE) {
            executeAnswerCycle(run);
        } else if (run.triggerType() == AgentRunTriggerType.NODE_QUERY) {
            executeNodeQuery(run);
        } else {
            executeDecisionCycle(run);
        }
    }

    /**
     * Stage A decision cycle: STATE_UPDATE + DECISION, record proposal only.
     */
    private void executeDecisionCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                    run.projectId(), runId, ContextOperationType.NORMAL);
            agentRunService.attachContext(runId, snapshot.id(), "decision-cycle:context_built");
            eventService.append(runId, AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Context guard rejected agent run");
            }

            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    runId,
                    snapshot,
                    new AgentEvent("CONTINUE", snapshot.tipNodeId(), null, null),
                    new DecisionBudget(2));

            eventService.append(runId, AgentRunPhase.STATE_UPDATING, "STATE_UPDATE_STARTED", Map.of());
            AgentResponseEnvelope stateUpdate = decisionEngine.runStateUpdate(envelope);
            eventService.append(runId, AgentRunPhase.STATE_UPDATED, "STATE_UPDATE_COMPLETED", Map.of(
                    "claimCount", stateUpdate.stateUpdate() == null
                            ? 0 : stateUpdate.stateUpdate().claims().size(),
                    "modelCalls", stateUpdate.usage() == null ? 0 : stateUpdate.usage().modelCalls()));

            eventService.append(runId, AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            AgentResponseEnvelope decision = decisionEngine.runDecision(envelope);
            ActionProposal proposal = decision.actionProposal();
            eventService.append(runId, AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                    "actionFamily", proposal.actionFamily(),
                    "sourceRefCount", proposal.sourceRefs().size(),
                    "modelCalls", decision.usage() == null ? 0 : decision.usage().modelCalls()));
            // Stage A stops here: proposals are recorded, never executed.

            agentRunService.complete(runId, AgentRunStatus.COMPLETED, "decision-cycle:completed");
            eventService.append(runId, AgentRunPhase.COMPLETED, "RUN_COMPLETED", Map.of());
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

            if ("RESUME_ANSWER".equals(operation) && answerId != null) {
                answerCycleService.resumeAnswer(run, run.projectId(), answerId);
            } else {
                answerCycleService.submitAnswer(run, run.projectId(), selectedOptionId, freeText);
            }
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
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
            UUID routeId = input.containsKey("routeId")
                    ? UUID.fromString((String) input.get("routeId")) : run.routeId();
            UUID nodeId = input.containsKey("nodeId")
                    ? UUID.fromString((String) input.get("nodeId")) : run.inputNodeId();
            String question = (String) input.get("question");
            if (routeId == null || nodeId == null || question == null) {
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
