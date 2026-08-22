package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.gates.ContextGuard;
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

import java.util.Map;
import java.util.UUID;

/**
 * Background executor for one decision-cycle run.
 *
 * <p>Runs the full cycle against the {@link AgentDecisionEngine} port: freeze
 * a context snapshot, project it into the input snapshot, run STATE_UPDATE
 * and DECISION, and record every phase as an append-only run event. Stage A
 * records proposals only — nothing is executed against the Graph, so there is
 * no product-visible behavior change.
 *
 * <p>The worker never touches a model gateway or provider package directly;
 * all model access stays behind the decision engine boundary.
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

    public RunWorker(RunService runService,
                            AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            ContextBuilder contextBuilder,
                            ContextGuard contextGuard,
                            AgentInputSnapshotBuilder snapshotBuilder,
                            AgentDecisionEngine decisionEngine,
                            AgentRunEventService eventService) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.eventService = eventService;
    }

    /** Claims and executes at most one queued run; used by the poller loop. */
    public void tryClaimAndExecute() {
        runService.claimNext().ifPresent(this::executeRun);
    }

    /**
     * Executes one claimed run to a terminal state. Unexpected failures mark
     * the run FAILED in their own transaction so it stays queryable, then
     * rethrow.
     */
    public void executeRun(AgentRun run) {
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

    private void failIfNotTerminal(UUID runId, RuntimeException ex) {
        String step = failureStepFor(ex);
        LOG.warn("Agent run {} failed: {}", runId, step);
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, "decision-cycle:failed:" + step);
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED", Map.of("reason", step));
        }
    }

    /**
     * Safe failure category only — never exception messages that could echo
     * provider payloads or brain response bodies.
     */
    private String failureStepFor(RuntimeException ex) {
        if (ex instanceof AgentBrainUnavailableException) {
            return "brain_unavailable";
        }
        return ex.getClass().getSimpleName();
    }
}
