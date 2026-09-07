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
import com.specagent.agent.eligibility.ActionEligibilityGate;
import com.specagent.agent.gates.ContextGuard;
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
 * Autonomous continuation execution: exactly 1 fresh DECISION, no
 * STATE_UPDATE.
 *
 * <p>A continuation child records its causal anchor as {@code inputNodeId}
 * (the parent's resulting tip at creation time). Execution re-anchors here:
 * the live route tip must still equal it, otherwise the child goes stale
 * before any model call — newer external causality is never followed.
 *
 * <p>Observation is always a fresh snapshot built from current Runtime
 * truth (graph, claims, answers/patches, capability results). The parent
 * snapshot is never replayed. The DECISION tail (validate → eligibility →
 * policy → execute → terminalize) is entirely
 * {@link DecisionExecutionService}; this service only prepares the
 * continuation input. It never reads semantic planner fields, branches on
 * action families, or decides continuation — the next boundary belongs to
 * the coordinator's post-commit hook, never to this run.
 */
@Service
public class ContinuationCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationCycleService.class);

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

    public ContinuationCycleService(AgentRunService agentRunService,
                                    AgentRunFailureService agentRunFailureService,
                                    ContextBuilder contextBuilder,
                                    ContextGuard contextGuard,
                                    AgentInputSnapshotBuilder snapshotBuilder,
                                    AgentRunEventService eventService,
                                    DecisionExecutionService decisionExecution,
                                    ProjectRepository projectRepository,
                                    RouteRepository routeRepository,
                                    ActionEligibilityGate actionEligibilityGate) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.eventService = eventService;
        this.decisionExecution = decisionExecution;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.actionEligibilityGate = actionEligibilityGate;
    }

    /** Post-run view over one continuation cycle. */
    public record ContinuationCycleResult(UUID runId, UUID producedNodeId,
                                          UUID proposalId, String outcome) {
    }

    /**
     * Executes one continuation run: re-anchor, fresh observe, one DECISION.
     */
    public ContinuationCycleResult executeContinuation(AgentRun run) {
        Route route = loadContinuationTargetRoute(run);
        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = contextBuilder.buildForRoute(
                    run.projectId(), route.id(), route.tipNodeId(), run.id(),
                    ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            // Route/tip re-anchor above pins the execution target; the shared
            // guard re-validates the fresh snapshot (route exists, OPEN,
            // still the active route, hash present). No guard logic is copied
            // here — an active-route switch after child creation rejects here
            // with zero model calls, zero actions, zero children.
            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Context guard rejected continuation run");
            }

            // Fresh continuation: one DECISION call, never a STATE_UPDATE.
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
                            trace, "\n", Map.of(
                                    "snapshotId", snapshot.id().toString(),
                                    "contextHash", snapshot.contextHash()));
            String outcome = executed.outcome();
            UUID proposalId = "awaiting_approval".equals(outcome)
                    ? executed.proposalId() : null;
            return new ContinuationCycleResult(run.id(), executed.producedNodeId(),
                    proposalId, outcome);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Loads and re-anchors the continuation target: the run's route must
     * still belong to the project, and the live tip must still equal the
     * {@code inputNodeId} anchor recorded at child creation. A moved tip
     * fails closed here — before any snapshot, model call, or mutation —
     * and the failure terminalizes the run, so no further child follows.
     */
    private Route loadContinuationTargetRoute(AgentRun run) {
        Project project = projectRepository.findById(run.projectId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Project not found: " + run.projectId()));
        Route route = routeRepository.findById(run.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + run.routeId()));
        if (!route.projectId().equals(project.id())) {
            throw new IllegalStateException(
                    "Continuation route does not belong to project: " + run.routeId());
        }
        if (!Objects.equals(run.inputNodeId(), route.tipNodeId())) {
            throw new StaleRunTargetException(
                    "Continuation anchor moved since child " + run.id()
                            + " was created: expected " + run.inputNodeId()
                            + " but live tip is " + route.tipNodeId());
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
        return trace == null || trace.isBlank() ? step : trace + "\n" + step;
    }
}
