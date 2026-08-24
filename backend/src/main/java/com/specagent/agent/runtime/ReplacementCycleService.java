package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.ModelContractException;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.StaleContextChecker;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.route.RegenerateResult;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replacement cycle: exactly 1 DECISION call producing the replacement
 * question CONTENT, then a deterministic runtime commit. The model never
 * mutates the graph here — the replacement topology (old route SUPERSEDED,
 * new OPEN route with a fresh identity, source-route provenance) stays owned
 * by {@code RouteService.commitReplacementFromNode}, and the proposal's base
 * context must still be the live snapshot before the commit.
 *
 * <p>Fail-closed guards: the target node must still sit in the source
 * route's lineage at execution time, and the replacement question must
 * differ from the rejected question.
 */
@Service
public class ReplacementCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ReplacementCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AgentRunEventService eventService;
    private final StaleContextChecker staleContextChecker;
    private final NodeService nodeService;
    private final RouteRepository routeRepository;
    private final RouteService routeService;

    public ReplacementCycleService(AgentRunService agentRunService,
                                   AgentRunFailureService agentRunFailureService,
                                   ContextBuilder contextBuilder,
                                   ContextGuard contextGuard,
                                   AgentInputSnapshotBuilder snapshotBuilder,
                                   AgentDecisionEngine decisionEngine,
                                   AgentRunEventService eventService,
                                   StaleContextChecker staleContextChecker,
                                   NodeService nodeService,
                                   RouteRepository routeRepository,
                                   RouteService routeService) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.eventService = eventService;
        this.staleContextChecker = staleContextChecker;
        this.nodeService = nodeService;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
    }

    /**
     * Executes one regeneration run: frozen replacement context, one DECISION,
     * deterministic topology commit.
     */
    public RegenerateResult regenerate(AgentRun run, UUID projectId,
                                       UUID sourceRouteId, UUID targetNodeId,
                                       String userInstruction) {
        Node targetNode = nodeService.getNode(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Target node does not belong to project");
        }
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + sourceRouteId));

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = contextBuilder.buildForReplacement(
                    projectId, sourceRouteId, targetNodeId);
            agentRunService.attachContext(run.id(), snapshot.id(), trace);
            eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            if (!contextGuard.validate(snapshot).accepted()) {
                throw new ModelContractException("Replacement context rejected");
            }

            // The user instruction rides on the event's free text; the anchor
            // is the rejected target node itself.
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot,
                    new AgentEvent("CONTINUE", targetNodeId, null, userInstruction),
                    new DecisionBudget(1));

            trace = appendTrace(trace, "deciding");
            eventService.append(run.id(), AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            AgentResponseEnvelope response = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, response);
            ActionProposal proposal = response.actionProposal();
            eventService.append(run.id(), AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED",
                    Map.of("actionFamily", proposal.actionFamily(),
                           "proposalId", proposal.proposalId().toString()));

            if (!"REQUEST_USER_INPUT".equals(proposal.actionFamily())) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:unexpected_action"));
                throw new ModelContractException(
                        "Expected REQUEST_USER_INPUT from replacement DECISION");
            }

            Map<String, Object> payload = proposal.payload();
            String question = stringOrNull(payload.get("questionText"));
            String purpose = stringOrNull(payload.get("purpose"));
            boolean allowFreeAnswer = payload.get("allowFreeAnswer") instanceof Boolean b && b;
            List<NodeOption> options = parseOptions(payload.get("options"));

            if (question == null || question.isBlank()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:empty_question"));
                throw new ModelContractException("Replacement question must not be blank");
            }
            if (normalize(question).equals(normalize(targetNode.question()))) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:duplicate_question"));
                throw new ModelContractException(
                        "Replacement question must differ from the rejected question");
            }

            // The proposal was built against the frozen replacement snapshot;
            // it must still be the live one before any topology commits.
            ActionExecutionContext execContext = new ActionExecutionContext(
                    run.id(), projectId, sourceRouteId, snapshot.id(),
                    targetNodeId, null, userInstruction);
            staleContextChecker.check(proposal, execContext, snapshot);

            trace = appendTrace(trace, "committing_replacement");
            eventService.append(run.id(), AgentRunPhase.EXECUTING, "EXECUTING",
                    Map.of("actionFamily", "COMMIT_REPLACEMENT"));

            RegenerateResult result = routeService.commitReplacementFromNode(
                    projectId, sourceRouteId, targetNodeId, null,
                    question, purpose, options, allowFreeAnswer);

            // Freeze the durable regenerate context onto the replacement
            // route (parent lineage only, target excluded) — the same record
            // the deterministic command always persisted.
            contextBuilder.buildForRegenerate(projectId, sourceRouteId, targetNodeId,
                    result.replacementRoute().id(), result.replacementNode().id(),
                    userInstruction);

            trace = appendTrace(trace, "persisted_node");
            agentRunService.markPersistedNode(run.id(), result.replacementNode().id(), trace);
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED", Map.of(
                    "producedNodeId", result.replacementNode().id().toString()));

            return result;
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private List<NodeOption> parseOptions(Object optionsObj) {
        if (!(optionsObj instanceof List<?> optionList)) {
            return List.of();
        }
        List<NodeOption> options = new ArrayList<>();
        for (Object item : optionList) {
            if (item instanceof Map<?, ?> map && map.get("label") instanceof String label) {
                options.add(NodeOption.of(label, stringOrNull(map.get("impact"))));
            }
        }
        return options;
    }

    private String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
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
}
