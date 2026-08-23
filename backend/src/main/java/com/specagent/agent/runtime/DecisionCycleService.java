package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.action.StaleContextChecker;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure-continuation reasoning: exactly 1 DECISION call, no STATE_UPDATE.
 *
 * <p>Used for question drafting ({@code DRAFT_QUESTION}): there is no new
 * Answer to interpret, so the cycle goes straight from the frozen context
 * snapshot to one DECISION, then through the same fail-closed chain as the
 * answer cycle — validator → policy → auto-execute / persist proposal +
 * AWAITING_APPROVAL / deny. A {@code REQUEST_USER_INPUT} proposal executes as
 * an INTERACTION node appended at the route tip (or the route's root node on
 * an empty route); the runtime owns every ID and the tip advancement.
 *
 * <p>Fail-closed guards mirror the answer cycle: the run's recorded target
 * (tip at enqueue time) must still be the active route tip at execution, and
 * the active route must not have changed underneath the queued run.
 */
@Service
public class DecisionCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunEventService eventService;
    private final StaleContextChecker staleContextChecker;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;

    public DecisionCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                AgentDecisionEngine decisionEngine,
                                AdvisorPolicyEngine policyEngine,
                                ActionExecutor actionExecutor,
                                AgentProposalService proposalService,
                                AgentRunEventService eventService,
                                StaleContextChecker staleContextChecker,
                                ProjectRepository projectRepository,
                                RouteRepository routeRepository) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.eventService = eventService;
        this.staleContextChecker = staleContextChecker;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
    }

    /**
     * Executes one question-draft run: single DECISION, then the shared
     * policy/execution chain.
     */
    public DecisionCycleResult draftQuestion(AgentRun run) {
        Route route = loadDraftTargetRoute(run);
        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                    run.projectId(), run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Context guard rejected agent run");
            }

            // Pure continuation: one DECISION call, never a mechanical
            // STATE_UPDATE (there is no Answer to interpret).
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot,
                    new AgentEvent("CONTINUE", route.tipNodeId(), null, null),
                    new DecisionBudget(1));

            eventService.append(run.id(), AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            AgentResponseEnvelope decision = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, decision);

            ActionProposal proposal = decision.actionProposal();
            eventService.append(run.id(), AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                    "actionFamily", proposal.actionFamily(),
                    "proposalId", proposal.proposalId().toString()));

            return applyPolicyAndExecute(run, route, snapshot, proposal, trace);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Shared policy + execution closure — the same deny / AWAITING_APPROVAL /
     * auto-execute semantics as the answer cycle, minus the answer artifacts.
     */
    private DecisionCycleResult applyPolicyAndExecute(AgentRun run, Route route,
                                                      ContextSnapshot snapshot,
                                                      ActionProposal proposal, String trace) {
        ActionExecutionContext execContext = new ActionExecutionContext(
                run.id(), run.projectId(), route.id(), snapshot.id(),
                route.tipNodeId(), null, null);

        PolicyDecision policyDecision = policyEngine.evaluate(proposal, execContext);

        // A confirmation verdict for a proposal that could never be executed
        // after acceptance is downgraded to a deny — no clickable-but-dead
        // proposals are ever persisted.
        if (policyDecision.requiresConfirmation()
                && !policyEngine.canProduceAcceptableProposal(proposal, execContext)) {
            policyDecision = PolicyDecision.deny(policyDecision.classification(),
                    "提案在本阶段无法在确认后执行: " + proposal.actionFamily());
        }

        if (policyDecision.denyReason() != null) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, run.id(), run.projectId(), route.id());
            if (agentProposal.status() == ProposalStatus.PROPOSED) {
                proposalService.expireProposal(agentProposal.id());
            }
            trace = appendTrace(trace, "policy_denied:" + policyDecision.denyReason());
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            return new DecisionCycleResult(run.id(), null, null,
                    "policy_denied:" + policyDecision.denyReason());
        }

        if (policyDecision.requiresConfirmation()) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, run.id(), run.projectId(), route.id());
            trace = appendTrace(trace, "awaiting_approval:" + agentProposal.id());
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.AWAITING_APPROVAL,
                    "AWAITING_APPROVAL", Map.of(
                            "proposalId", agentProposal.id().toString()));
            return new DecisionCycleResult(run.id(), null, agentProposal.id(),
                    "awaiting_approval");
        }

        // Auto-execute: the proposal's base context must still be the live
        // snapshot before any mutation.
        staleContextChecker.check(proposal, execContext, snapshot);
        trace = appendTrace(trace, "executing");
        eventService.append(run.id(), AgentRunPhase.EXECUTING,
                "EXECUTING", Map.of("actionFamily", proposal.actionFamily()));

        ActionResult execResult = actionExecutor.execute(proposal, execContext);
        trace = appendTrace(trace, "completed");

        if (execResult.producedNodeId() != null) {
            agentRunService.markPersistedNode(run.id(), execResult.producedNodeId(), trace);
        }
        agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
        Map<String, Object> completedPayload = new HashMap<>();
        completedPayload.put("actionFamily", proposal.actionFamily());
        if (execResult.producedNodeId() != null) {
            completedPayload.put("producedNodeId", execResult.producedNodeId().toString());
        }
        eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED", completedPayload);

        return new DecisionCycleResult(run.id(), execResult.producedNodeId(), null,
                "completed");
    }

    /**
     * Loads and validates the run's draft target: the run's route must still
     * be the active route and the tip recorded at enqueue time must still be
     * the tip. Both may be null together (root draft on an empty route).
     */
    private Route loadDraftTargetRoute(AgentRun run) {
        Project project = projectRepository.findById(run.projectId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Project not found: " + run.projectId()));
        if (!project.activeRouteId().equals(run.routeId())) {
            throw new IllegalStateException(
                    "Draft target route is no longer the active route: " + run.routeId());
        }
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + run.routeId()));
        if (!Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new IllegalStateException(
                    "Draft target is no longer the active route tip: " + run.inputNodeId());
        }
        return route;
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        LOG.warn("Agent run {} failed at {}: {}", runId, trace, ex.getMessage());
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, appendTrace(trace, "failed"));
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED",
                    Map.of("reason", ex.getClass().getSimpleName()));
        }
    }

    private String appendTrace(String trace, String step) {
        return trace + ">" + step;
    }

    /** Post-run view over one question-draft cycle. */
    public record DecisionCycleResult(UUID runId, UUID producedNodeId,
                                      UUID proposalId, String outcome) {
    }
}
