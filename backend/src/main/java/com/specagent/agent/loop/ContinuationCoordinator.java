package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.NodeQueryService;
import com.specagent.agent.runtime.StaleRunTargetException;
import com.specagent.answer.AnswerService;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityResult;
import com.specagent.node.Node;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether one terminal AgentRun may spawn an autonomous
 * continuation child (Slice 2+). The only question answered here is
 * "can another cycle legally start" — never "what should it do".
 *
 * <p>Input is a run id only; every fact is re-read from durable state, so
 * {@code evaluate(runId)} returns the same answer after a process restart.
 * No parameter carries model-generated text, policy objects, or
 * precomputed decisions. Chain identity is lazy: a null
 * {@code rootRunId}/{@code cycleIndex} reads as "own root at cycle 0", so
 * external creation paths need no loop metadata.
 *
 * <p>Future child creation (Slice 2) records {@code expectedStartTipNodeId}
 * in the child's {@code RUN_CREATED} event payload (the established channel
 * for execution inputs such as route and node ids — no new column): the
 * child executes only while the live route tip still equals that anchor,
 * otherwise it goes stale instead of following a newer external causal
 * chain.
 */
@Component
public class ContinuationCoordinator {

    private final AgentRunService agentRunService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunEventService eventService;
    private final AgentProposalService proposalService;
    private final CapabilityInvocationRepository invocationRepository;
    private final NodeRepository nodeRepository;
    private final AnswerService answerService;
    private final LoopProperties loopProperties;
    private final RunService runService;

    public ContinuationCoordinator(AgentRunService agentRunService,
                                   AgentRunRepository agentRunRepository,
                                   AgentRunEventService eventService,
                                   AgentProposalService proposalService,
                                   CapabilityInvocationRepository invocationRepository,
                                   NodeRepository nodeRepository,
                                   AnswerService answerService,
                                   LoopProperties loopProperties,
                                   RunService runService) {
        this.agentRunService = agentRunService;
        this.agentRunRepository = agentRunRepository;
        this.eventService = eventService;
        this.proposalService = proposalService;
        this.invocationRepository = invocationRepository;
        this.nodeRepository = nodeRepository;
        this.answerService = answerService;
        this.loopProperties = loopProperties;
        this.runService = runService;
    }

    /**
     * Creates the continuation child when (and only when) the run is
     * eligible, and returns it. A repeated call for the same parent
     * returns the already persisted child instead of creating a second
     * row: the deterministic {@code continue:<parentRunId>} key plus the
     * project-scoped idempotency unique index arbitrate concurrent
     * creators, so no Java-level check-then-insert exists here.
     *
    * <p>The child stays {@code CREATED}: claiming and executing it belongs
    * to the worker (Slice 3+), never to this call.
     *
     * <p>A stale anchor (live tip moved past the row-derived expected tip
     * since the parent completed) refuses creation and returns empty: the
     * chain parks instead of following external causality.
     */
    public Optional<AgentRun> continueIfEligible(UUID runId) {
        ContinuationDecision decision = evaluate(runId);
        if (decision.verdict() == ContinuationVerdict.ALREADY_CONTINUED) {
            return agentRunRepository.findChildByParentRunId(runId);
        }
        if (!decision.eligible()) {
            return Optional.empty();
        }
        AgentRun parent = agentRunService.getRun(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent run not found: " + runId));
        try {
            return Optional.of(runService.createContinueRun(parent).run());
        } catch (StaleRunTargetException stale) {
            return Optional.empty();
        }
    }

    /**
     * Judges one run from durable state. Terminal {@code FAILED} runs map
     * to {@code FAILED}; non-terminal runs are a caller error and throw.
     */
    public ContinuationDecision evaluate(UUID runId) {
        AgentRun run = agentRunService.getRun(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent run not found: " + runId));
        if (run.status() == AgentRunStatus.FAILED) {
            return ContinuationDecision.of(runId, ContinuationVerdict.FAILED,
                    "run terminally failed");
        }
        if (run.status() != AgentRunStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Continuation needs a terminal run: " + runId);
        }
        if (agentRunRepository.findChildByParentRunId(runId).isPresent()) {
            return ContinuationDecision.of(runId, ContinuationVerdict.ALREADY_CONTINUED,
                    "a continuation child already exists");
        }
        int effectiveCycle = run.cycleIndex() != null ? run.cycleIndex() : 0;
        if (effectiveCycle + 1 >= loopProperties.getMaxCycles()) {
            return ContinuationDecision.of(runId, ContinuationVerdict.BUDGET_EXHAUSTED,
                    "cycle " + effectiveCycle + " reaches max-cycles "
                            + loopProperties.getMaxCycles());
        }
        Optional<AgentProposal> proposal = proposalService.findByRunId(runId);
        if (proposal.isPresent() && proposal.get().status() == ProposalStatus.PROPOSED) {
            return ContinuationDecision.of(runId, ContinuationVerdict.PARKED_APPROVAL,
                    "proposal awaits decision: " + proposal.get().id());
        }
        if (isUnansweredQuestion(run)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.PARKED_USER_INPUT,
                    "produced question node awaits an answer: " + run.producedNodeId());
        }
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        if (events.stream().anyMatch(ContinuationCoordinator::isRespondMessage)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.TERMINAL_RESPONSE,
                    "run emitted a response message");
        }
        if (hasNewFacts(run)) {
            return ContinuationDecision.of(runId,
                    ContinuationVerdict.EXECUTED_NEW_OBSERVATION, "durable facts persisted");
        }
        if (isDenied(proposal, events)) {
            return ContinuationDecision.of(runId, ContinuationVerdict.DENIED,
                    "policy denied without durable change");
        }
        return ContinuationDecision.of(runId, ContinuationVerdict.NO_EFFECT,
                "no consumable durable effect");
    }

    /**
     * True when this run produced an interaction node that still waits for
     * an answer. Proven from the node row plus the absence of an answer
     * row — never from the requested action family name.
     */
    private boolean isUnansweredQuestion(AgentRun run) {
        if (run.producedNodeId() == null || run.routeId() == null) {
            return false;
        }
        Optional<Node> node = nodeRepository.findById(run.producedNodeId());
        return node.isPresent() && node.get().kind() == NodeKind.INTERACTION
                && !answerService.existsAnswerFor(run.routeId(), run.producedNodeId());
    }

    private static boolean isRespondMessage(AgentRunEvent event) {
        return NodeQueryService.RESPOND_MESSAGE_EVENT.equals(event.eventType());
    }

    /**
     * True when a next snapshot could consume something this run left
     * behind: produced rows on the run, or completed capability invocation
     * rows (successes and durable failures alike — failures persist as
     * evidence). Unfinished invocations do not count.
     */
    private boolean hasNewFacts(AgentRun run) {
        if (run.producedNodeId() != null || run.producedAnswerId() != null
                || run.producedPatchId() != null || run.producedSpecSnapshotId() != null) {
            return true;
        }
        return invocationRepository.findByRunId(run.id()).stream()
                .map(CapabilityInvocationRecord::status)
                .anyMatch(status -> status == CapabilityResult.Status.SUCCEEDED
                        || status == CapabilityResult.Status.FAILED);
    }

    /**
     * True when the run was denied without effect: an expired proposal left
     * by a deny branch, or a node-query deny/not-confirmable event.
     */
    private static boolean isDenied(Optional<AgentProposal> proposal,
                                    List<AgentRunEvent> events) {
        if (proposal.isPresent() && proposal.get().status() == ProposalStatus.EXPIRED) {
            return true;
        }
        return events.stream().anyMatch(event ->
                NodeQueryService.POLICY_DENIED_EVENT.equals(event.eventType())
                        || NodeQueryService.MUTATION_NOT_CONFIRMABLE_EVENT.equals(event.eventType()));
    }
}
