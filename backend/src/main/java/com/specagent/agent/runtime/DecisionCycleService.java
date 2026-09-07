package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.eligibility.ActionEligibilityGate;
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
    private final AgentRunEventService eventService;
    private final DecisionExecutionService decisionExecution;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ActionEligibilityGate actionEligibilityGate;

    public DecisionCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                DecisionExecutionService decisionExecution,
                                AgentRunEventService eventService,
                                ProjectRepository projectRepository,
                                RouteRepository routeRepository,
                                ActionEligibilityGate actionEligibilityGate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionExecution = decisionExecution;
        this.eventService = eventService;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.actionEligibilityGate = actionEligibilityGate;
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
            AgentRequestEnvelope envelope = actionEligibilityGate.prepareDecisionRequest(
                    snapshotBuilder.buildEnvelope(
                            run.id(), snapshot,
                            new AgentEvent("CONTINUE", route.tipNodeId(), null, null),
                            new DecisionBudget(1)));

            ActionExecutionContext execContext = new ActionExecutionContext(
                    run.id(), run.projectId(), route.id(), snapshot.id(),
                    route.tipNodeId(), null, null);
            DecisionExecutionService.DecisionExecutionResult executed =
                    decisionExecution.execute(snapshot, envelope, execContext,
                            trace, ">", Map.of());
            return new DecisionCycleResult(run.id(), executed.producedNodeId(),
                    "awaiting_approval".equals(executed.outcome())
                            ? executed.proposalId() : null,
                    executed.outcome());
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
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
