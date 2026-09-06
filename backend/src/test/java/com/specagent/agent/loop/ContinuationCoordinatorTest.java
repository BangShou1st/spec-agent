package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runtime.NodeQueryService;
import com.specagent.answer.AnswerService;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 1: {@code ContinuationCoordinator.evaluate} reads durable Runtime
 * truth only and never consults semantic fields.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContinuationCoordinatorTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private AgentProposalService proposalService;
    @Autowired
    private CapabilityInvocationRepository invocationRepository;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private ContinuationCoordinator coordinator;
    @Autowired
    private LoopProperties loopProperties;

    @Test
    void loopBudgetDefaultsToThreeTotalCycles() {
        assertThat(loopProperties.getMaxCycles()).isEqualTo(3);
    }

    @Test
    void succeededCapabilityResultIsEligible() {
        Fixture fx = completedFixture();
        UUID invocationId = UUID.randomUUID();
        String key = "coord-" + UUID.randomUUID();
        assertThat(invocationRepository.claim(new CapabilityInvocation(
                invocationId, key, "resource.extract_text",
                fx.project.id(), fx.runId, Map.of()))).isTrue();
        invocationRepository.complete(invocationId, new CapabilityResult(
                invocationId, key, "resource.extract_text",
                CapabilityResult.Status.SUCCEEDED, Map.of("excerpt", "evidence"),
                List.of(), Map.of(), List.of()));

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.verdict())
                .isEqualTo(ContinuationVerdict.EXECUTED_NEW_OBSERVATION);
    }

    @Test
    void failedCapabilityResultIsEligibleWhenDurable() {
        Fixture fx = completedFixture();
        UUID invocationId = UUID.randomUUID();
        String key = "coord-" + UUID.randomUUID();
        assertThat(invocationRepository.claim(new CapabilityInvocation(
                invocationId, key, "unknown.capability",
                fx.project.id(), fx.runId, Map.of()))).isTrue();
        invocationRepository.complete(invocationId, CapabilityResult.failed(
                invocationId, key, "unknown.capability", "Unknown capability id"));

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.verdict())
                .isEqualTo(ContinuationVerdict.EXECUTED_NEW_OBSERVATION);
    }

    @Test
    void unfinishedCapabilityAloneIsNotEligible() {
        Fixture fx = completedFixture();
        assertThat(invocationRepository.claim(new CapabilityInvocation(
                UUID.randomUUID(), "coord-" + UUID.randomUUID(), "resource.extract_text",
                fx.project.id(), fx.runId, Map.of()))).isTrue();

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.NO_EFFECT);
    }

    @Test
    void producedGraphDeltaIsEligible() {
        Project project = projectService.createProject("coord-delta");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), " produced?", null,
                List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "delta answer", "test-user");
        UUID runId = saveRun(project, AgentRunStatus.COMPLETED, root.id(),
                null, null, null);

        ContinuationDecision decision = coordinator.evaluate(runId);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.verdict())
                .isEqualTo(ContinuationVerdict.EXECUTED_NEW_OBSERVATION);
    }

    @Test
    void unansweredQuestionParksForUserInput() {
        Project project = projectService.createProject("coord-parked");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "unanswered?", null,
                List.of(), true);
        UUID runId = saveRun(project, AgentRunStatus.COMPLETED, root.id(),
                null, null, null);

        assertThat(coordinator.evaluate(runId).verdict())
                .isEqualTo(ContinuationVerdict.PARKED_USER_INPUT);

        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "answered", "test-user");

        ContinuationDecision after = coordinator.evaluate(runId);
        assertThat(after.verdict())
                .isEqualTo(ContinuationVerdict.EXECUTED_NEW_OBSERVATION);
        assertThat(after.eligible()).isTrue();
    }

    @Test
    void pendingProposalParksForApproval() {
        Fixture fx = completedFixture();
        proposalService.createProposal(new ActionProposal(
                "CREATE_NODE", Map.of("kind", "INTERACTION"),
                UUID.randomUUID(), "hash",
                List.of(), UUID.randomUUID(), "key-" + UUID.randomUUID(), List.of()),
                fx.runId, fx.project.id(), fx.project.activeRouteId());

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.PARKED_APPROVAL);
    }

    @Test
    void respondMessageEventIsTerminalResponse() {
        Fixture fx = completedFixture();
        eventService.append(fx.runId, AgentRunPhase.COMPLETED,
                NodeQueryService.RESPOND_MESSAGE_EVENT, Map.of("message", "hello"));

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.TERMINAL_RESPONSE);
    }

    @Test
    void expiredProposalWithoutFactsIsDenied() {
        Fixture fx = completedFixture();
        var proposal = proposalService.createProposal(new ActionProposal(
                "CREATE_ROUTE", Map.of(),
                UUID.randomUUID(), "hash",
                List.of(), UUID.randomUUID(), "key-" + UUID.randomUUID(), List.of()),
                fx.runId, fx.project.id(), fx.project.activeRouteId());
        proposalService.expireProposal(proposal.id());

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.DENIED);
    }

    @Test
    void policyDeniedEventIsDenied() {
        Fixture fx = completedFixture();
        eventService.append(fx.runId, AgentRunPhase.COMPLETED,
                NodeQueryService.POLICY_DENIED_EVENT,
                Map.of("denyReason", "reason", "actionFamily", "UPDATE_NODE"));

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.DENIED);
    }

    @Test
    void bareCompletedRunHasNoEffect() {
        Fixture fx = completedFixture();

        ContinuationDecision decision = coordinator.evaluate(fx.runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.NO_EFFECT);
    }

    @Test
    void failedRunIsFailed() {
        Project project = projectService.createProject("coord-failed");
        UUID runId = saveRun(project, AgentRunStatus.FAILED, null, null, null, null);

        ContinuationDecision decision = coordinator.evaluate(runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.FAILED);
    }

    @Test
    void nonTerminalRunIsRejected() {
        Project project = projectService.createProject("coord-running");
        UUID runId = saveRun(project, AgentRunStatus.RUNNING, null, null, null, null);

        assertThatThrownBy(() -> coordinator.evaluate(runId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exhaustedBudgetStopsTheChain() {
        Project project = projectService.createProject("coord-budget");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "budget?", null, List.of(), true);
        UUID runId = saveRun(project, AgentRunStatus.COMPLETED, root.id(), null, null, 2);

        ContinuationDecision decision = coordinator.evaluate(runId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.BUDGET_EXHAUSTED);
    }

    @Test
    void existingChildBlocksASecondChild() {
        Project project = projectService.createProject("coord-child");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "child?", null, List.of(), true);
        UUID parentId = saveRun(project, AgentRunStatus.COMPLETED, root.id(),
                null, null, null);
        agentRunRepository.save(new AgentRun(UUID.randomUUID(), project.id(),
                project.activeRouteId(), AgentRunTriggerType.CONTINUE_CYCLE,
                null, null, null, null, null, null, AgentRunStatus.CREATED,
                "{}", "CONTINUE", "continue-" + parentId, "fp", Instant.now(), null,
                parentId, parentId, 1));

        ContinuationDecision decision = coordinator.evaluate(parentId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.verdict()).isEqualTo(ContinuationVerdict.ALREADY_CONTINUED);
    }

    @Test
    void extraGraphTextDoesNotChangeTheVerdict() {
        Fixture fx = completedFixture();
        ContinuationDecision before = coordinator.evaluate(fx.runId);
        assertThat(before.verdict()).isEqualTo(ContinuationVerdict.NO_EFFECT);

        nodeService.createRootNode(fx.project.id(), fx.project.activeRouteId(),
                "conflict: scope A excludes scope B, unknowns remain, risks recorded",
                null, List.of(), true);

        assertThat(coordinator.evaluate(fx.runId).verdict())
                .isEqualTo(ContinuationVerdict.NO_EFFECT);
    }

    @Test
    void unknownRunIsRejected() {
        assertThatThrownBy(() -> coordinator.evaluate(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID saveRun(Project project, AgentRunStatus status, UUID producedNodeId,
                         UUID parentRunId, UUID rootRunId, Integer cycleIndex) {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, producedNodeId, null, null, null, status,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                parentRunId, rootRunId, cycleIndex));
        return runId;
    }

    private Fixture completedFixture() {
        Project project = projectService.createProject("coord");
        UUID runId = saveRun(project, AgentRunStatus.COMPLETED, null, null, null, null);
        return new Fixture(project, runId);
    }

    private final class Fixture {
        final Project project;
        final UUID runId;

        Fixture(Project project, UUID runId) {
            this.project = project;
            this.runId = runId;
        }
    }
}
