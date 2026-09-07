package com.specagent.agent.runtime;

import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTerminalizationService;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.action.StaleContextChecker;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.eligibility.ActionEligibilityGate;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.context.ContextSnapshot;
import com.specagent.trace.SemanticTraceRecorder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared tail of one prepared DECISION: run the model call through the
 * Runtime fail-closed chain to a terminal run.
 *
 * <p>Covers exactly the segment both the question-draft cycle and the answer
 * cycle already execute line-for-line: DECISION invocation, response
 * validation, eligibility assess/enforce, {@code PROPOSAL_CREATED}, policy
 * evaluation (including the confirmation-executability downgrade), deny /
 * awaiting-approval branches, stale check, {@code ActionExecutor},
 * produced-node persistence, completion, and {@code RUN_COMPLETED}.
 *
 * <p>Everything before this segment stays with the caller: route/target
 * loading, active-route validation, input-node stale-anchor checks, context
 * building, guards, Answer persistence, STATE_UPDATE, AnswerPatch, post-state
 * snapshot reconstruction, event semantics, and budget selection all differ
 * per cycle and are never rebuilt here. The core takes already-prepared
 * Runtime objects (plain typed parameters, no mega-context), never reads
 * Answer/Patch rows, semantic planner fields, trigger types, or action
 * families to branch, and never touches continuation.
 */
@Service
public class DecisionExecutionService {

    private final AgentDecisionEngine decisionEngine;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunTerminalizationService terminalizationService;
    private final AgentRunEventService eventService;
    private final StaleContextChecker staleContextChecker;
    private final ActionEligibilityGate actionEligibilityGate;
    private final SemanticTraceRecorder semanticTraceRecorder;

    public DecisionExecutionService(AgentDecisionEngine decisionEngine,
                                    AdvisorPolicyEngine policyEngine,
                                    ActionExecutor actionExecutor,
                                    AgentProposalService proposalService,
                                    AgentRunTerminalizationService terminalizationService,
                                    AgentRunEventService eventService,
                                    StaleContextChecker staleContextChecker,
                                    ActionEligibilityGate actionEligibilityGate,
                                    SemanticTraceRecorder semanticTraceRecorder) {
        this.decisionEngine = decisionEngine;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.terminalizationService = terminalizationService;
        this.eventService = eventService;
        this.staleContextChecker = staleContextChecker;
        this.actionEligibilityGate = actionEligibilityGate;
        this.semanticTraceRecorder = semanticTraceRecorder;
    }

    /**
     * Executes one prepared DECISION to a terminal run.
     *
     * <p>Run/project/route identity comes from {@code execContext} alone —
     * there is no second copy to drift. The envelope and snapshot must agree
     * with it (fail-closed on mismatch) since a prepared input built for one
     * run must never execute as another.
     *
     * @param snapshot frozen snapshot the envelope was built from
     * @param envelope prepared DECISION request (budget and event already set
     *                 by the caller)
     * @param execContext execution context for policy and the executor; also
     *                 the single source of run/project/route identity
     * @param trace caller-owned lifecycle trace; the returned trace appends
     *              only this segment's steps with the caller's separator
     * @param traceSeparator separator the caller uses between trace steps
     * @param decisionStartedPayload payload for the {@code DECISION_STARTED}
     *              event. The answer cycle records its post-state snapshot
     *              identity here (repair replay anchor); the question-draft
     *              cycle records an empty payload. The content differs per
     *              cycle by design and is owned by the caller.
     */
    public DecisionExecutionResult execute(ContextSnapshot snapshot,
                                           AgentRequestEnvelope envelope,
                                           ActionExecutionContext execContext,
                                           String trace,
                                           String traceSeparator,
                                           Map<String, Object> decisionStartedPayload) {
        UUID runId = execContext.runId();
        UUID projectId = execContext.projectId();
        UUID routeId = execContext.routeId();
        if (!Objects.equals(envelope.runId(), runId)
                || !Objects.equals(snapshot.id(), execContext.contextSnapshotId())
                || !Objects.equals(snapshot.projectId(), projectId)
                || !Objects.equals(snapshot.routeId(), routeId)) {
            throw new IllegalStateException(
                    "Prepared DECISION input does not match its execution context "
                            + "for run " + runId);
        }
        semanticTraceRecorder.captureDecisionInput(envelope);

        eventService.append(runId, AgentRunPhase.DECIDING, "DECISION_STARTED",
                decisionStartedPayload);
        AgentResponseEnvelope decision;
        try {
            decision = decisionEngine.runDecision(envelope);
            AgentBrainResponseValidator.validateDecision(envelope, decision);
            semanticTraceRecorder.captureDecisionOutput(decision);
        } catch (RuntimeException ex) {
            semanticTraceRecorder.captureFailure(runId, "DECISION_OUTPUT", ex);
            throw ex;
        }

        ActionProposal proposal = decision.actionProposal();
        ActionEligibilityGate.Assessment eligibilityAssessment =
                actionEligibilityGate.assess(envelope, proposal);
        semanticTraceRecorder.captureActionEligibility(
                runId, envelope, decision, eligibilityAssessment);
        actionEligibilityGate.enforce(eligibilityAssessment);
        eventService.append(runId, AgentRunPhase.PROPOSAL_CREATED, "PROPOSAL_CREATED", Map.of(
                "actionFamily", proposal.actionFamily(),
                "proposalId", proposal.proposalId().toString()));

        PolicyDecision policyDecision = policyEngine.evaluate(proposal, execContext);

        // A confirmation verdict for a proposal that could never be executed
        // after acceptance is downgraded to a deny — no clickable-but-dead
        // proposals are ever persisted.
        if (policyDecision.requiresConfirmation()
                && !policyEngine.canProduceAcceptableProposal(proposal, execContext)) {
            policyDecision = PolicyDecision.deny(policyDecision.classification(),
                    "提案在本阶段无法在确认后执行: " + proposal.actionFamily());
        }
        semanticTraceRecorder.capturePolicyDecision(runId, policyDecision);

        if (policyDecision.denyReason() != null) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, runId, projectId, routeId);
            if (agentProposal.status() == ProposalStatus.PROPOSED) {
                proposalService.expireProposal(agentProposal.id());
            }
            trace = appendTrace(trace, "policy_denied:" + policyDecision.denyReason(), traceSeparator);
            terminalizationService.completeWithCheck(runId, AgentRunStatus.COMPLETED, trace);
            return new DecisionExecutionResult(runId, null, agentProposal.id(),
                    "policy_denied:" + policyDecision.denyReason(), trace);
        }

        if (policyDecision.requiresConfirmation()) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, runId, projectId, routeId);
            trace = appendTrace(trace, "awaiting_approval:" + agentProposal.id(), traceSeparator);
            terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED, trace,
                    AgentRunPhase.AWAITING_APPROVAL, "AWAITING_APPROVAL", Map.of(
                            "proposalId", agentProposal.id().toString()));
            return new DecisionExecutionResult(runId, null, agentProposal.id(),
                    "awaiting_approval", trace);
        }

        // Auto-execute: the proposal's base context must still be the live
        // snapshot before any mutation.
        staleContextChecker.check(proposal, execContext, snapshot);
        trace = appendTrace(trace, "executing", traceSeparator);
        eventService.append(runId, AgentRunPhase.EXECUTING,
                "EXECUTING", Map.of("actionFamily", proposal.actionFamily()));

        ActionResult execResult = actionExecutor.execute(proposal, execContext);
        trace = appendTrace(trace, "completed", traceSeparator);

        Map<String, Object> completedPayload = new HashMap<>();
        completedPayload.put("actionFamily", proposal.actionFamily());
        if (execResult.producedNodeId() != null) {
            completedPayload.put("producedNodeId", execResult.producedNodeId().toString());
        }
        terminalizationService.completeWithNodeAndEvent(runId, AgentRunStatus.COMPLETED, trace,
                execResult.producedNodeId(), AgentRunPhase.COMPLETED, "RUN_COMPLETED",
                completedPayload);

        return new DecisionExecutionResult(runId, execResult.producedNodeId(), null,
                "completed", trace);
    }

    private String appendTrace(String trace, String step, String separator) {
        if (trace == null || trace.isBlank()) {
            return step;
        }
        return trace + separator + step;
    }

    /**
     * Runtime-neutral outcome of one shared DECISION execution. Carries only
     * what every cycle needs; cycle-specific artifacts (answer/patch ids)
     * stay with the caller.
     */
    public record DecisionExecutionResult(UUID runId,
                                          UUID producedNodeId,
                                          UUID proposalId,
                                          String outcome,
                                          String trace) {
    }
}
