package com.specagent.agent.runtime;

import com.specagent.agent.*;
import com.specagent.agent.action.ActionExecutor;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.action.ActionResult;
import com.specagent.agent.contract.*;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.PatchReflectionGate;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.policy.AdvisorPolicyEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.PolicyDecision;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answer cycle: exactly 2 serial provider calls (STATE_UPDATE + DECISION),
 * replacing the legacy 3-call path (INTERPRET_ANSWER + DRAFT_ANSWER_PATCH +
 * DRAFT_NODE).
 *
 * <p>Flow:
 * <ol>
 *   <li>Persist immutable Answer (before any model call).</li>
 *   <li>Check for existing AnswerPatch (repair gate).</li>
 *   <li>If no patch: Call 1 STATE_UPDATE → grounded claims → persist patch checkpoint.</li>
 *   <li>Rebuild a post-state snapshot containing that Answer/Patch.</li>
 *   <li>Call 2 DECISION from the post-state snapshot → observation + action proposal.</li>
 *   <li>Policy evaluation → auto-execute or propose for confirmation.</li>
 * </ol>
 *
 * <p>Preserves repair semantics: once the Answer exists, retry resumes from
 * the safe patch checkpoint and never creates a second Answer.
 */
@Service
public class AnswerCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(AnswerCycleService.class);

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final PatchReflectionGate patchReflectionGate;
    private final AdvisorPolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AgentProposalService proposalService;
    private final AgentRunEventService eventService;
    private final NodeService nodeService;
    private final RouteRepository routeRepository;
    private final com.specagent.agent.action.StaleContextChecker staleContextChecker;
    private final com.specagent.project.ProjectRepository projectRepository;

    public AnswerCycleService(AgentRunService agentRunService,
                              AgentRunFailureService agentRunFailureService,
                              ContextBuilder contextBuilder,
                              AgentInputSnapshotBuilder snapshotBuilder,
                              AgentDecisionEngine decisionEngine,
                              AnswerService answerService,
                              AnswerPatchService answerPatchService,
                              PatchReflectionGate patchReflectionGate,
                              AdvisorPolicyEngine policyEngine,
                              ActionExecutor actionExecutor,
                              AgentProposalService proposalService,
                              AgentRunEventService eventService,
                              NodeService nodeService,
                              RouteRepository routeRepository,
                              com.specagent.agent.action.StaleContextChecker staleContextChecker,
                              com.specagent.project.ProjectRepository projectRepository) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.patchReflectionGate = patchReflectionGate;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.proposalService = proposalService;
        this.eventService = eventService;
        this.nodeService = nodeService;
        this.routeRepository = routeRepository;
        this.staleContextChecker = staleContextChecker;
        this.projectRepository = projectRepository;
    }

    /**
     * Executes the answer cycle for a submitted answer.
     *
     * <p>Input validation mirrors the legacy orchestrator contract: the
     * selected option must belong to the exact node being answered, free text
     * is only accepted when the node allows it, and at least one meaningful
     * input is required. All checks run before any Answer is persisted.
     */
    public AnswerCycleResult submitAnswer(AgentRun run, UUID projectId,
                                          UUID selectedOptionId, String freeText) {
        Route route = loadActiveRoute(projectId);
        if (route.tipNodeId() == null) {
            throw new IllegalStateException("Active route has no tip node");
        }
        Node tipNode = nodeService.getNode(route.tipNodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Active tip node not found: " + route.tipNodeId()));

        // A queued run records its input node at enqueue time. If the graph
        // moved on before the worker claimed the run, fail instead of
        // answering a different node than the user was looking at.
        if (run.inputNodeId() != null && !run.inputNodeId().equals(route.tipNodeId())) {
            throw new IllegalStateException(
                    "Answer target is no longer the active route tip: " + run.inputNodeId());
        }

        String selectedOption = validateSelectedOption(tipNode, selectedOptionId);
        String normalizedFreeText = normalizeFreeText(freeText);
        validateAnswerInput(tipNode, selectedOption, normalizedFreeText);

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = buildAndValidateContext(run, projectId, trace);

            // Persist immutable Answer BEFORE any model call.
            Answer answer = answerService.finalizeAnswer(
                    projectId, route.id(), route.tipNodeId(),
                    selectedOption, normalizedFreeText, "user");
            trace = appendTrace(trace, "persisted_answer");
            agentRunService.markPersistedAnswer(run.id(), answer.id(), trace);

            // Build envelope with answer event.
            AgentEvent event = new AgentEvent(
                    "ANSWER_SUBMITTED", route.tipNodeId(),
                    selectedOptionId, normalizedFreeText);
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot, event, new DecisionBudget(2));

            // STATE_UPDATE + DECISION + policy + execute.
            return completeCycle(run, projectId, route, snapshot, envelope,
                    answer, selectedOptionId, normalizedFreeText, trace);

        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Resumes an existing answer whose processing failed. The answer is
     * already persisted, so it is never finalized again.
     *
     * <p>Semantic replay guarantee: the resumed DECISION envelope is rebuilt
     * from the immutable persisted Answer, so the original user input — the
     * ANSWER_SUBMITTED event kind, selected option, free text and source
     * node — is identical to the first attempt. Resume never degrades into a
     * context-free CONTINUE, and the persisted patch checkpoint (if any) is
     * reused without re-running STATE_UPDATE.
     */
    public AnswerCycleResult resumeAnswer(AgentRun run, UUID projectId, UUID answerId) {
        Answer answer = answerService.getAnswer(answerId)
                .orElseThrow(() -> new IllegalArgumentException("Answer not found: " + answerId));
        if (!answer.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Answer does not belong to project: " + projectId);
        }

        Route route = loadActiveRoute(projectId);
        if (!answer.routeId().equals(route.id())) {
            throw new IllegalStateException("Answer does not belong to active route");
        }
        if (!answer.nodeId().equals(route.tipNodeId())) {
            throw new IllegalStateException("Answer node is not the active route tip");
        }

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = buildAndValidateContext(run, projectId, trace);

            trace = appendTrace(trace, "persisted_answer");
            agentRunService.markPersistedAnswer(run.id(), answer.id(), trace);

            // Rebuild the original submission semantics from the persisted
            // Answer — not from caller-supplied input and never as a bare
            // CONTINUE.
            UUID selectedOptionId = answer.selectedOptionId() == null
                    ? null : UUID.fromString(answer.selectedOptionId());
            String freeText = answer.freeText();
            AgentEvent event = new AgentEvent(
                    "ANSWER_SUBMITTED", answer.nodeId(), selectedOptionId, freeText);
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot, event, new DecisionBudget(2));

            return completeCycle(run, projectId, route, snapshot, envelope,
                    answer, selectedOptionId, freeText, trace);

        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Shared post-answer processing: STATE_UPDATE → patch checkpoint →
     * post-state DECISION snapshot → DECISION → policy → execute. Eliminates
     * duplication between submitAnswer and resumeAnswer.
     */
    private AnswerCycleResult completeCycle(AgentRun run, UUID projectId,
                                            Route route, ContextSnapshot snapshot,
                                            AgentRequestEnvelope envelope,
                                            Answer answer,
                                            UUID selectedOptionId, String freeText,
                                            String trace) {
        // Check for existing patch (repair gate).
        AnswerPatch patch = answerPatchService.findBySourceAnswerId(answer.id()).orElse(null);

        // Call 1: STATE_UPDATE (unless patch already exists).
        if (patch == null) {
            patch = runStateUpdate(run, projectId, route, envelope, answer, trace);
            trace = appendTrace(trace, "persisted_patch");
        } else {
            trace = appendTrace(trace, "reused_persisted_patch:" + patch.id());
            agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
            eventService.append(run.id(), AgentRunPhase.STATE_UPDATED,
                    "STATE_UPDATE_SKIPPED", Map.of("reason", "patch_exists"));
        }

        // STATE_UPDATE is a durable checkpoint. DECISION must read the state
        // AFTER that checkpoint, not the pre-answer snapshot used for the
        // first model call. Rebuild against the exact route (never whatever
        // route happens to be active now) so the new Answer/Patch/effective
        // claims are causally visible while route isolation remains fail-closed.
        ContextSnapshot decisionSnapshot = contextBuilder.buildForRoute(
                projectId, route.id(), route.tipNodeId(), run.id(), ContextOperationType.NORMAL);
        AgentRequestEnvelope decisionEnvelope = snapshotBuilder.buildEnvelope(
                run.id(), decisionSnapshot, envelope.event(), envelope.decisionBudget());

        // Call 2: DECISION.
        trace = appendTrace(trace, "deciding");
        eventService.append(run.id(), AgentRunPhase.DECIDING,
                "DECISION_STARTED", Map.of());

        AgentResponseEnvelope decisionResponse = decisionEngine.runDecision(decisionEnvelope);
        AgentBrainResponseValidator.validateDecision(decisionEnvelope, decisionResponse);

        ActionProposal proposal = decisionResponse.actionProposal();
        eventService.append(run.id(), AgentRunPhase.PROPOSAL_CREATED,
                "PROPOSAL_CREATED", Map.of(
                        "actionFamily", proposal.actionFamily(),
                        "proposalId", proposal.proposalId().toString()));

        // Policy evaluation.
        ActionExecutionContext execContext = new ActionExecutionContext(
                run.id(), projectId, route.id(), decisionSnapshot.id(),
                route.tipNodeId(), selectedOptionId, freeText);

        PolicyDecision policyDecision = policyEngine.evaluate(proposal, execContext);

        // Contract closure: a confirmation verdict for a proposal that could
        // never be executed after acceptance is downgraded to a deny, so no
        // clickable-but-unexecutable proposal is ever persisted.
        if (policyDecision.requiresConfirmation()
                && !policyEngine.canProduceAcceptableProposal(proposal, execContext)) {
            policyDecision = PolicyDecision.deny(policyDecision.classification(),
                    "提案在本阶段无法在确认后执行: " + proposal.actionFamily());
        }

        if (policyDecision.denyReason() != null) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, run.id(), projectId, route.id());
            // Idempotent deny: on a retry the proposal row already exists and
            // may have been expired by an earlier attempt. Expiring it again
            // must stay a no-op here, not a lifecycle-race failure.
            if (agentProposal.status() == ProposalStatus.PROPOSED) {
                proposalService.expireProposal(agentProposal.id());
            }
            trace = appendTrace(trace, "policy_denied:" + policyDecision.denyReason());
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            return new AnswerCycleResult(run.id(), answer.id(), patch.id(),
                    null, "policy_denied:" + policyDecision.denyReason());
        }

        if (policyDecision.requiresConfirmation()) {
            AgentProposal agentProposal = proposalService.createProposal(
                    proposal, run.id(), projectId, route.id());
            trace = appendTrace(trace, "awaiting_approval:" + agentProposal.id());
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.AWAITING_APPROVAL,
                    "AWAITING_APPROVAL", Map.of(
                            "proposalId", agentProposal.id().toString()));
            return new AnswerCycleResult(run.id(), answer.id(), patch.id(),
                    agentProposal.id(), "awaiting_approval");
        }

        // Auto-execute: verify the proposal's base context is still the live
        // post-state snapshot before any mutation (stale proposals are
        // rejected, never silently rebased onto newer graph state).
        staleContextChecker.check(proposal, execContext, decisionSnapshot);
        trace = appendTrace(trace, "executing");
        eventService.append(run.id(), AgentRunPhase.EXECUTING,
                "EXECUTING", Map.of("actionFamily", proposal.actionFamily()));

        ActionResult execResult = actionExecutor.execute(proposal, execContext);
        trace = appendTrace(trace, "completed");

        if (execResult.producedNodeId() != null) {
            agentRunService.markPersistedNode(run.id(), execResult.producedNodeId(), trace);
        }
        agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
        Map<String, Object> completedPayload = new java.util.HashMap<>();
        completedPayload.put("actionFamily", proposal.actionFamily());
        if (execResult.producedNodeId() != null) {
            completedPayload.put("producedNodeId", execResult.producedNodeId().toString());
        }
        eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED", completedPayload);

        return new AnswerCycleResult(run.id(), answer.id(), patch.id(),
                execResult.producedNodeId(), "completed");
    }

    /**
     * Runs STATE_UPDATE, grounds claims, validates, and persists patch checkpoint.
     * Returns the persisted patch.
     */
    private AnswerPatch runStateUpdate(AgentRun run, UUID projectId,
                                       Route route, AgentRequestEnvelope envelope,
                                       Answer answer, String trace) {
        trace = appendTrace(trace, "state_updating");
        eventService.append(run.id(), AgentRunPhase.STATE_UPDATING,
                "STATE_UPDATE_STARTED", Map.of());

        AgentResponseEnvelope stateUpdateResponse = decisionEngine.runStateUpdate(envelope);
        AgentBrainResponseValidator.validateStateUpdate(envelope, stateUpdateResponse);

        eventService.append(run.id(), AgentRunPhase.STATE_UPDATED,
                "STATE_UPDATE_COMPLETED", Map.of(
                        "claimCount", stateUpdateResponse.stateUpdate() == null
                                ? 0 : stateUpdateResponse.stateUpdate().claims().size()));

        List<Claim> groundedClaims = groundClaims(
                stateUpdateResponse.stateUpdate().claims(),
                route.tipNodeId(), answer.id());

        ReflectionResult patchReflection = patchReflectionGate.validate(
                new com.specagent.agent.contracts.AnswerPatchDraft(groundedClaims));
        agentRunService.markReflected(run.id(), trace);

        if (!patchReflection.accepted()) {
            agentRunService.fail(run.id(),
                    appendTrace(trace, "failed:patch_reflection_rejected"));
            throw new ModelContractException(
                    "Patch reflection rejected: " + patchReflection.errors());
        }

        AnswerPatch patch = answerPatchService.save(
                projectId, route.id(), route.tipNodeId(), answer.id(),
                groundedClaims, run.id());
        agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
        return patch;
    }

    private List<Claim> groundClaims(List<ProposedClaim> proposedClaims,
                                     UUID sourceNodeId, UUID sourceAnswerId) {
        List<Claim> grounded = new ArrayList<>();
        for (ProposedClaim pc : proposedClaims) {
            ClaimKind kind = ClaimKind.fromCode(pc.kind());
            ClaimStatus status = ClaimStatus.fromCode(pc.status());
            if (status == ClaimStatus.CONFIRMED) {
                grounded.add(new Claim(null, kind, pc.text(), status,
                        pc.confidence(), sourceNodeId, sourceAnswerId));
            } else {
                grounded.add(new Claim(null, kind, pc.text(), status,
                        pc.confidence(), null, null));
            }
        }
        return grounded;
    }

    /**
     * Validates a client-selected option id against the exact answering node.
     * The client may only reference an existing runtime-owned option id
     * previously returned by that node; ids from other nodes, sibling routes,
     * or random fabrication are rejected before any answer is persisted.
     */
    private String validateSelectedOption(Node tipNode, UUID selectedOptionId) {
        if (selectedOptionId == null) {
            return null;
        }
        return tipNode.options().stream()
                .filter(option -> option.id().equals(selectedOptionId))
                .findFirst()
                .map(option -> option.id().toString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Selected option id does not belong to the active node"));
    }

    private String normalizeFreeText(String freeText) {
        return (freeText == null || freeText.isBlank()) ? null : freeText;
    }

    /**
     * Enforces the answer input policy: at least one meaningful input (a valid
     * selected option or non-blank free text) is required, and non-blank free
     * text is rejected when the node does not allow free-form answers.
     */
    private void validateAnswerInput(Node tipNode, String selectedOption, String freeText) {
        if (selectedOption == null && freeText == null) {
            throw new IllegalArgumentException("Answer requires a selected option or free text");
        }
        if (freeText != null && !tipNode.allowFreeAnswer()) {
            throw new IllegalArgumentException("This node does not allow free-form answers");
        }
    }

    private Route loadActiveRoute(UUID projectId) {
        com.specagent.project.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        return routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));
    }

    private ContextSnapshot buildAndValidateContext(AgentRun run, UUID projectId, String trace) {
        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                projectId, run.id(), ContextOperationType.NORMAL);
        agentRunService.attachContext(run.id(), snapshot.id(), trace);
        eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT,
                "SNAPSHOT_BUILT", Map.of(
                        "snapshotId", snapshot.id().toString(),
                        "contextHash", snapshot.contextHash()));
        return snapshot;
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String persisted = latest.trace();
            String base = (persisted == null || persisted.isBlank()) ? trace : persisted;
            String step = ex instanceof com.specagent.agent.decision.AgentBrainUnavailableException
                    ? "brain_unavailable" : ex.getClass().getSimpleName();
            agentRunFailureService.fail(runId, appendTrace(base, "failed:" + step));
            eventService.append(runId, AgentRunPhase.FAILED,
                    "RUN_FAILED", Map.of("reason", step));
        }
    }

    private String appendTrace(String trace, String step) {
        return trace == null || trace.isBlank() ? step : trace + "\n" + step;
    }
}
