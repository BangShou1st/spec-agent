package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunFailureService;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Contextual AI query on an arbitrary node: exactly one DECISION call.
 *
 * <p>This is the "ask AI about this node" path of the graph workspace model.
 * The context is the anchor node's lineage plus the explicit read route;
 * the answer must come back as {@code RESPOND_TO_USER} (or {@code WAIT}) and
 * never mutates the graph. If the model proposes a mutation action instead,
 * it is persisted as an Advisor proposal awaiting user confirmation — a query
 * has no side effects.
 */
@Service
public class NodeQueryService {

    public static final String RESPOND_MESSAGE_EVENT = "RESPOND_MESSAGE";

    private static final Logger LOG = LoggerFactory.getLogger(NodeQueryService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunEventService eventService;

    public NodeQueryService(AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            ContextBuilder contextBuilder,
                            AgentInputSnapshotBuilder snapshotBuilder,
                            AgentDecisionEngine decisionEngine,
                            AdvisorPolicyEngine policyEngine,
                            ActionExecutor actionExecutor,
                            AgentProposalService proposalService,
                            AgentRunEventService eventService) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.eventService = eventService;
    }

    public record NodeQueryResult(UUID runId, String status, String message, UUID proposalId) {
    }

    /**
     * Executes the node query: snapshot → 1 DECISION call → policy.
     */
    public NodeQueryResult executeNodeQuery(AgentRun run, UUID routeId,
                                            UUID anchorNodeId, String question) {
        UUID runId = run.id();
        String trace = "created";
        try {
            ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                    run.projectId(), routeId, anchorNodeId, question);
            agentRunService.attachContext(runId, snapshot.id(), trace);
            eventService.append(runId, AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of(
                    "snapshotId", snapshot.id().toString(),
                    "contextHash", snapshot.contextHash()));

            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    runId, snapshot,
                    new AgentEvent("NODE_QUERY", anchorNodeId, null, question),
                    new DecisionBudget(1));

            eventService.append(runId, AgentRunPhase.DECIDING, "DECISION_STARTED", Map.of());
            AgentResponseEnvelope decision = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, decision);

            ActionProposal proposal = decision.actionProposal();
            eventService.append(runId, AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                    "actionFamily", proposal.actionFamily(),
                    "proposalId", proposal.proposalId().toString()));

            PolicyDecision policyDecision = policyEngine.evaluate(proposal, new ActionExecutionContext(
                    runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question));
            if (policyDecision.denyReason() != null) {
                trace = trace + "\npolicy_denied:" + policyDecision.denyReason();
                agentRunService.complete(runId, AgentRunStatus.COMPLETED, trace);
                return new NodeQueryResult(runId, "policy_denied", null, null);
            }

            // A query never mutates the graph: read-only families execute,
            // every confirmable mutation family is downgraded to a pending
            // proposal. Families that could never be executed after
            // acceptance (no command path, non-tip anchor, dead endpoints)
            // are reported as not confirmable instead of creating a
            // clickable-but-unexecutable proposal.
            boolean readOnly = switch (ActionFamily.fromCode(proposal.actionFamily())) {
                case RESPOND_TO_USER, WAIT -> true;
                case CREATE_NODE, UPDATE_NODE, CONNECT_NODE, CREATE_ROUTE,
                     REQUEST_USER_INPUT, INVOKE_CAPABILITY, GENERATE_ARTIFACT -> false;
            };
            if (!readOnly) {
                ActionExecutionContext downgradeContext = new ActionExecutionContext(
                        runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question);
                if (!policyEngine.canProduceAcceptableProposal(proposal, downgradeContext)) {
                    trace = trace + "\nnot_confirmable:" + proposal.actionFamily();
                    agentRunService.complete(runId, AgentRunStatus.COMPLETED, trace);
                    eventService.append(runId, AgentRunPhase.COMPLETED, "MUTATION_NOT_CONFIRMABLE",
                            Map.of("actionFamily", proposal.actionFamily()));
                    return new NodeQueryResult(runId, "not_confirmable", null, null);
                }
                AgentProposal agentProposal = proposalService.createProposal(
                        proposal, runId, run.projectId(), routeId);
                agentRunService.complete(runId, AgentRunStatus.COMPLETED,
                        trace + "\nawaiting_approval:" + agentProposal.id());
                eventService.append(runId, AgentRunPhase.AWAITING_APPROVAL,
                        "AWAITING_APPROVAL", Map.of(
                                "proposalId", agentProposal.id().toString(),
                                "actionFamily", proposal.actionFamily()));
                return new NodeQueryResult(runId, "awaiting_approval", null, agentProposal.id());
            }

            eventService.append(runId, AgentRunPhase.EXECUTING, "EXECUTING",
                    Map.of("actionFamily", proposal.actionFamily()));
            ActionResult result = actionExecutor.execute(proposal, new ActionExecutionContext(
                    runId, run.projectId(), routeId, snapshot.id(), anchorNodeId, null, question));

            if (result.message() != null) {
                eventService.append(runId, AgentRunPhase.COMPLETED,
                        RESPOND_MESSAGE_EVENT, Map.of("message", result.message()));
            }
            agentRunService.complete(runId, AgentRunStatus.COMPLETED, trace + "\ncompleted");
            eventService.append(runId, AgentRunPhase.COMPLETED, "RUN_COMPLETED", Map.of());
            return new NodeQueryResult(runId, "completed", result.message(), null);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    private void failIfNotTerminal(UUID runId, RuntimeException ex) {
        String step = ex instanceof AgentBrainUnavailableException
                ? "brain_unavailable" : ex.getClass().getSimpleName();
        LOG.warn("Node query run {} failed: {}", runId, step);
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            agentRunFailureService.fail(runId, "failed:" + step);
            eventService.append(runId, AgentRunPhase.FAILED, "RUN_FAILED", Map.of("reason", step));
        }
    }
}
