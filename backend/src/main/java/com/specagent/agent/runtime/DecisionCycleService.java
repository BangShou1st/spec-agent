package com.specagent.agent.runtime;

import com.specagent.agent.gates.ContextGuard;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
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
    private final AnswerProcessingGate answerProcessingGate;

    public DecisionCycleService(AgentRunService agentRunService,
                                AgentRunFailureService agentRunFailureService,
                                ContextBuilder contextBuilder,
                                ContextGuard contextGuard,
                                AgentInputSnapshotBuilder snapshotBuilder,
                                DecisionExecutionService decisionExecution,
                                AgentRunEventService eventService,
                                ProjectRepository projectRepository,
                                RouteRepository routeRepository,
                                ActionEligibilityGate actionEligibilityGate,
                                AnswerProcessingGate answerProcessingGate) {
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
        this.answerProcessingGate = answerProcessingGate;
    }

    /**
     * Executes one question-draft run: single DECISION, then the shared
     * policy/execution chain.
     */
    public DecisionCycleResult draftQuestion(AgentRun run) {
        return draftQuestion(run, null);
    }

    /**
     * Question draft with an optional EXPLICIT target route.
     *
     * <p>With {@code explicitRouteId != null} the run drafts on its own route
     * (context built per-route, Active-equality skipped in the guard), which is
     * what lets route B keep generating while route A is the Active route.
     * {@code null} keeps the original Active-route behaviour exactly.
     */
    public DecisionCycleResult draftQuestion(AgentRun run, UUID explicitRouteId) {
        Route route = loadDraftTargetRoute(run, explicitRouteId);
        boolean explicitRoute = explicitRouteId != null;
        String trace = "created";
        try {
            // DRAFT_QUESTION advances the route tip. Re-check immediately
            // before building/calling the model so a run queued while the
            // answer was still complete cannot cross a newly visible missing
            // STATE_UPDATE checkpoint.
            answerProcessingGate.firstUnprocessedAnswer(route.id(), route.tipNodeId())
                    .ifPresent(pending -> {
                        throw new IncompleteAnswerCycleException(
                                "Answer " + pending.id() + " on route "
                                        + pending.routeId() + " has no processed state update",
                                pending.id(), pending.routeId(), pending.nodeId());
                    });
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = explicitRoute
                    ? contextBuilder.buildForRoute(
                            run.projectId(), route.id(), route.tipNodeId(), run.id(),
                            ContextOperationType.NORMAL)
                    : contextBuilder.buildFromActiveRoute(
                            run.projectId(), run.id(), ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot, explicitRoute).accepted()) {
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
     * Loads and validates the run's draft target: the tip recorded at enqueue
     * time must still be the route's tip. Both may be null together (root
     * draft on an empty route).
     *
     * <p>In Active mode (the default) the run's route must ALSO still be the
     * project Active route. In explicit mode that equality is deliberately not
     * required — the run owns its route — while ownership and OPEN-ness are
     * still enforced by {@code RunService.resolveTargetRoute} at enqueue time
     * and re-validated here through the route lookup.
     */
    private Route loadDraftTargetRoute(AgentRun run, UUID explicitRouteId) {
        if (explicitRouteId == null) {
            Project project = projectRepository.findById(run.projectId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Project not found: " + run.projectId()));
            if (!project.activeRouteId().equals(run.routeId())) {
                throw new IllegalStateException(
                        "Draft target route is no longer the active route: " + run.routeId());
            }
        }
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + run.routeId()));
        if (explicitRouteId == null && !Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new IllegalStateException(
                    "Draft target is no longer the active route tip: " + run.inputNodeId());
        }
        if (explicitRouteId != null && !Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new IllegalStateException(
                    "Draft target is not the tip of route " + run.routeId() + ": " + run.inputNodeId());
        }
        return route;
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        LOG.warn("Agent run {} failed at {}: {}", runId, trace, ex.getMessage());
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String reason = RunFailureReasons.reasonCode(ex);
            agentRunFailureService.fail(runId, appendTrace(trace, "failed:" + reason));
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED",
                    RunFailureReasons.payload(ex));
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
