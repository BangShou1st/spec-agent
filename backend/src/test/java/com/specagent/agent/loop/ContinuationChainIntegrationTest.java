package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.route.RouteService;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Slice 3B: a created continuation child really executes one fresh
 * Observe → DECISION → Act cycle through the production chain.
 *
 * <p>Deliberately NOT {@code @Transactional}: the worker's terminal
 * continuation hook evaluates after commit, and a rolled-back test
 * transaction would never fire it. Fixtures are removed per project in
 * {@link #cleanUp()}.
 *
 * <p>No test branches on action family names, conflict text, or planner
 * flags. Continuation is judged only through the coordinator's durable
 * verdicts; the fake brain decides the next proposal.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContinuationChainIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private AgentRunEventService eventService;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private LoopProperties loopProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.specagent.answer.AnswerService answerService;
    @SpyBean
    private ContinuationCheckRepository checkRepository;
    @Autowired private com.specagent.route.RouteService routeService;

    @SpyBean
    private ContinuationDispatchService dispatchService;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<UUID> projectIds = new ArrayList<>();
    private int configuredMaxCycles = -1;

    @AfterEach
    void cleanUp() {
        if (configuredMaxCycles > 0) {
            loopProperties.setMaxCycles(configuredMaxCycles);
            configuredMaxCycles = -1;
        }
        Mockito.reset(decisionEngine);
        for (UUID projectId : projectIds) {
            jdbcTemplate.update(
                    "DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                            + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                    (Object) projectId);
            jdbcTemplate.update(
                    "DELETE FROM agent_run_events WHERE run_id IN "
                            + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?",
                    (Object) projectId);
            jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?",
                    (Object) projectId);
        }
        projectIds.clear();
    }

    @Test
    void capabilitySuccessChainsToTerminalChild() {
        Project project = newProjectWithResource("chain-success");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability success")).id();

        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the continuation child to be claimable"));
        worker.executeRun(childClaimed);

        assertThat(decisions.get()).isEqualTo(2);
        assertThat(agentRunService.getRun(root.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        // The child's fresh snapshot observed the parent's capability result.
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.SUCCEEDED.name()));
        // The terminal child leaves no grandchild.
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(agentRunService.listByProject(project.id())).hasSize(2);
    }

    @Test
    void failedCapabilityIsVisibleToContinuationChild() {
        Project project = newProjectWithResource("chain-failed");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return brokenCapabilityInvoke(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability failure")).id();

        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the continuation child to be claimable"));
        worker.executeRun(childClaimed);

        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.FAILED.name()));
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
    }

    @Test
    void maxCyclesOneStopsAtRoot() {
        Project project = newProjectWithResource("chain-budget-1");
        withMaxCycles(1);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        assertThat(agentRunService.getRun(root.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void maxCyclesTwoStopsAfterOneChild() {
        Project project = newProjectWithResource("chain-budget-2");
        withMaxCycles(2);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected one continuation child")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(agentRunService.listByProject(project.id())).hasSize(2);
    }

    @Test
    void staleChildFailsClosedWithoutModelCall() {
        Project project = newProjectWithResource("chain-stale");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // External causality moves the tip after the child was created.
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                "external question moves the tip?", null, List.of(), true);

        int decisionsBefore = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the stale child to stay claimable"));
        try {
            worker.executeRun(childClaimed);
        } catch (RuntimeException expected) {
            // Stale anchor fails closed; the worker still terminalizes.
        }

        int decisionsAfter = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfter).isEqualTo(decisionsBefore);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(eventService.findByRunId(childId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType()))).isFalse();
    }

    @Test
    void repeatedTerminalHookKeepsExactlyOneChild() {
        Project project = newProjectWithResource("chain-exactly-once");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id();

        // A retried terminal hook (worker retry, duplicate delivery) must
        // converge on the same persisted child, never a second row. The
        // duplicate goes through dispatch — never by re-executing the
        // terminal COMPLETED run's model/action path.
        dispatchService.process(root.id());

        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(childId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void graphMutationChainsToChildSeeingFreshNode() {
        Project project = newProjectWithResource("chain-graph");
        // An unanswered question cannot gain a lineage child: answer the
        // root first so the stubbed CREATE_NODE may append.
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                routeBefore.tipNodeId(), null, "answered for append", "test-user");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID produced = agentRunService.getRun(root.id()).orElseThrow()
                .producedNodeId();
        assertThat(produced).isNotNull();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after graph mutation")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        assertThat(snapshotBuilder.build(snapshot).lineage().stream()
                .map(entry -> entry.node().id()))
                .contains(produced);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
    }

    @Test
    void userInputQuestionParksWithoutChild() {
        // No stub: the production fake brain answers a draft with a
        // user-input question, which the executor persists as an
        // INTERACTION node — a permanent external boundary.
        Project project = newProjectWithoutResource("chain-user-boundary");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.producedNodeId()).isNotNull();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void pendingApprovalParksWithoutChild() {
        Project project = newProjectWithResource("chain-approval");
        withMaxCycles(5);
        Mockito.doAnswer(invocation ->
                createDecisionNodeProposal(invocation.getArgument(0)))
                .when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(root.id()).stream()
                .anyMatch(e -> "AWAITING_APPROVAL".equals(e.eventType()))).isTrue();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void deniedProposalCreatesNoChild() {
        Project project = newProjectWithResource("chain-denied");
        withMaxCycles(5);
        Mockito.doAnswer(invocation ->
                updateNodeProposal(invocation.getArgument(0)))
                .when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.trace()).contains("policy_denied");
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
    }

    @Test
    void childDecisionSeesParentCapabilityResultInRequest() {
        Project project = newProjectWithResource("chain-fresh-observe");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // Proof is the child's ACTUAL DECISION input — not a rebuilt test
        // snapshot: the second runDecision request carries the parent's
        // SUCCEEDED capability result. (The capability fixture produces no
        // graph node, so lineage proof lives in the graph-mutation test.)
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.SUCCEEDED.name()));
    }

    @Test
    void childDecisionSeesParentFailedCapabilityResultInRequest() {
        Project project = newProjectWithResource("chain-fresh-observe-failed");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return brokenCapabilityInvoke(request);
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after capability failure")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // Same actual-input proof for the FAILED side: durable failures
        // persist as evidence and enter the child's real DECISION input.
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().capabilityResults())
                .anyMatch(view -> view.status().equals(
                        CapabilityResult.Status.FAILED.name()));
    }

    @Test
    void childDecisionSeesParentGraphMutationInRequestLineage() {
        Project project = newProjectWithResource("chain-fresh-observe-graph");
        // An unanswered question cannot gain a lineage child: answer the
        // root first so the stubbed CREATE_NODE may append.
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                routeBefore.tipNodeId(), null, "answered for append", "test-user");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID produced = agentRunService.getRun(root.id()).orElseThrow()
                .producedNodeId();
        assertThat(produced).isNotNull();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child after graph mutation")).id();

        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());

        // Proof is the child's ACTUAL DECISION input lineage — not a
        // rebuilt test snapshot: the second request's lineage contains the
        // node Run1 durably produced.
        var requests = Mockito.mockingDetails(decisionEngine)
                .getInvocations().stream()
                .filter(call -> "runDecision".equals(call.getMethod().getName()))
                .map(call -> (AgentRequestEnvelope) call.getArgument(0))
                .toList();
        assertThat(requests).hasSize(2);
        AgentRequestEnvelope second = requests.get(1);
        assertThat(second.snapshot().lineage().stream()
                .map(entry -> entry.node().id()))
                .contains(produced);
    }

    @Test
    void completedRunNeverReexecutesModelOrAction() {
        Project project = newProjectWithResource("chain-fail-closed");
        withMaxCycles(5);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        int decisionsAfterFirstRun = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();

        // A duplicate delivery of the terminal COMPLETED run must fail
        // closed — never re-run model/action. Recovery goes through
        // dispatch, never through executeRun on a terminal row.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> worker.executeRun(
                                runService.getRun(root.id()).orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
        int decisionsAfterDuplicate = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfterDuplicate).isEqualTo(decisionsAfterFirstRun);
    }

    @Test
    void lostAfterCommitRecoversPendingCheckToChild() {
        Project project = newProjectWithResource("chain-recover");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        // Simulate a crash after COMMIT but before afterCommit dispatch: the
        // terminal check row is pending while no child exists yet. Recovery
        // must create exactly one child and mark the check processed. The
        // fast-path child is removed in FK order (events first, then the
        // run) so the replay starts from the same durable state as a crash.
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isPresent();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id();
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", childId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", childId);
        jdbcTemplate.update(
                "INSERT INTO agent_run_continuation_checks"
                        + " (run_id, requested_at, processed_at, request_generation)"
                        + " VALUES (?, CURRENT_TIMESTAMP, NULL, 1)"
                        + " ON CONFLICT (run_id) DO UPDATE"
                        + " SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,"
                        + " request_generation ="
                        + " agent_run_continuation_checks.request_generation + 1",
                root.id());

        dispatchService.recoverPending();

        UUID recovered = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must create the missing child")).id();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(recovered))
                .orElseThrow());
        assertThat(agentRunService.getRun(recovered).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void recoveryWithExistingChildMarksProcessedWithoutSecondChild() {
        Project project = newProjectWithResource("chain-recover-dedup");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // Simulate a crash between child creation and markProcessed: the
        // check is still pending while the child already exists. Recovery
        // must converge via ALREADY_CONTINUED — no second child.
        jdbcTemplate.update(
                "INSERT INTO agent_run_continuation_checks"
                        + " (run_id, requested_at, processed_at, request_generation)"
                        + " VALUES (?, CURRENT_TIMESTAMP, NULL, 1)"
                        + " ON CONFLICT (run_id) DO UPDATE"
                        + " SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,"
                        + " request_generation ="
                        + " agent_run_continuation_checks.request_generation + 1",
                root.id());

        dispatchService.recoverPending();

        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(childId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
    }

    @Test
    void staleGenerationCompletionNeverMarksSupersedingRequest() {
        Project project = newProjectWithResource("chain-generation-race");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // Rebuild the exact ABA interleaving deterministically: the fast
        // path already consumed generation 1, so remove the child (FK order)
        // and re-request twice — generation 2 pins the "in-flight stale"
        // completion while generation 3 is the superseding request.
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", childId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", childId);
        dispatchService.request(root.id());
        ContinuationCheck generationStale = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check after re-request"));

        // Slice 6 preview: approval accept re-requests evaluation of the
        // same terminal run. The generation must increment and reopen.
        dispatchService.request(root.id());
        ContinuationCheck generationNew = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check after second re-request"));
        assertThat(generationNew.generation())
                .isEqualTo(generationStale.generation() + 1);

        // The stale completion runs first: it creates the child, but its
        // generation-gated mark targets the OLD generation against a row
        // that already carries the NEW one — 0 rows marked.
        dispatchService.process(generationStale);
        UUID firstChild = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "stale completion must still create the child")).id();

        // The new generation MUST remain pending — the stale completion
        // must not have consumed the superseding request.
        assertThat(checkRepository.findPendingByRunId(root.id()))
                .isPresent()
                .get().extracting(ContinuationCheck::generation)
                .isEqualTo(generationNew.generation());

        // Recovery converges the new generation: ALREADY_CONTINUED, same
        // child, exactly one row, and the check finally processed.
        dispatchService.recoverPending();
        assertThat(agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow().id()).isEqualTo(firstChild);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
    }

    @Test
    void failedFastPathDispatchLeavesRunCompletedAndCheckPending() {
        Project project = newProjectWithResource("chain-best-effort");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        // The immediate post-terminal dispatch throws: the failure must be
        // isolated to delivery — the parent run stays COMPLETED (never
        // rethrown as an execution failure) and the check stays pending.
        // doThrow().doCallRealMethod() scopes the failure to the fast path
        // only; the later recovery below runs the real dispatch.
        Mockito.doThrow(new IllegalStateException("dispatch transport down"))
                .doCallRealMethod()
                .when(dispatchService).process(any(UUID.class));

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        AgentRun completed = agentRunService.getRun(root.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        assertThat(checkRepository.findPendingByRunId(root.id())).isPresent();

        // Recovery later converges the still-pending check normally.
        dispatchService.recoverPending();
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must converge the deferred check")).id();
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();

        // The stub throws only once (doThrow().doCallRealMethod()): remove
        // it explicitly so no later test inherits the failure.
        Mockito.reset(dispatchService);
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow());
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void markPhaseFailureRollsBackChildCreationForSafeReplay() {
        Project project = newProjectWithResource("chain-mark-rollback");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        stubDecisionsAfterFirstWithTerminalRespond(decisions);

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));

        // The fast path already consumed generation 1: rebuild the crash
        // shape deterministically (no child, one pending generation).
        UUID fastChild = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected the fast-path child")).id();
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id = ?", fastChild);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE id = ?", fastChild);
        dispatchService.request(root.id());
        ContinuationCheck pending = checkRepository.findPendingByRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a pending check"));

        // Fail the mark phase AFTER the coordinator created the child: the
        // explicit TransactionTemplate transaction must roll the child back
        // together with the mark — a half-evaluation (child without mark)
        // must never commit under the final atomic design.
        Mockito.doThrow(new IllegalStateException("mark store unavailable"))
                .when(checkRepository).markProcessed(any(UUID.class), anyLong());
        try {
            dispatchService.process(pending);
            org.assertj.core.api.Assertions.fail(
                    "mark-phase failure must propagate for recovery retry");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("mark store unavailable");
        } finally {
            Mockito.reset(checkRepository);
        }

        // No child row survived the rollback, and the check is still
        // pending: recovery replays the same evaluation safely.
        assertThat(agentRunRepository.findChildByParentRunId(root.id())).isEmpty();
        assertThat(checkRepository.findPendingByRunId(root.id())).isPresent();

        dispatchService.recoverPending();
        UUID recovered = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "recovery must create the child after rollback")).id();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, root.id());
        assertThat(rowCount).isEqualTo(1);
        assertThat(checkRepository.findPendingByRunId(root.id())).isEmpty();
        worker.executeRun(runService.claimNextContinue()
                .filter(run -> run.id().equals(recovered))
                .orElseThrow());
        assertThat(agentRunService.getRun(recovered).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void activeRouteSwitchFailsContinuationClosedWithoutModelCall() {
        Project project = newProjectWithResource("chain-route-switch");
        var routeBefore = routeRepository.findById(project.activeRouteId()).orElseThrow();
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                routeBefore.tipNodeId(), null, "answered for append", "test-user");
        withMaxCycles(5);
        AtomicInteger decisions = new AtomicInteger();
        org.mockito.stubbing.Answer<Object> forward = invocation -> {
            AgentRequestEnvelope request = invocation.getArgument(0);
            if (decisions.getAndIncrement() == 0) {
                return createNoteProposal(request, "continuation observes this note");
            }
            return terminalRespond(request);
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());

        AgentRun root = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(claim(root));
        UUID childId = agentRunRepository.findChildByParentRunId(root.id())
                .orElseThrow(() -> new IllegalStateException(
                        "expected a continuation child")).id();

        // The active route switches while the child waits — but the old tip
        // is untouched, so the stale-anchor check alone would still pass. The
        // shared ContextGuard (active-route match) must reject instead. The
        // fork branch point needs a finalized answer, so answer the child's
        // produced tip first (it is the live tip after the root run).
        UUID liveTip = routeRepository.findById(project.activeRouteId())
                .orElseThrow().tipNodeId();
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                liveTip, null, "answered for fork", "test-user");
        var forked = routeService.forkFromNode(project.id(), project.activeRouteId(),
                liveTip, "switched");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(forked.id());

        int decisionsBefore = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        AgentRun childClaimed = runService.claimNextContinue()
                .filter(run -> run.id().equals(childId))
                .orElseThrow(() -> new IllegalStateException(
                        "expected the switched child to stay claimable"));
        try {
            worker.executeRun(childClaimed);
        } catch (RuntimeException expected) {
            // Guard rejection fails the run closed; the worker terminalizes.
        }

        int decisionsAfter = Mockito.mockingDetails(decisionEngine)
                .getInvocations().size();
        assertThat(decisionsAfter).isEqualTo(decisionsBefore);
        assertThat(agentRunService.getRun(childId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
        assertThat(eventService.findByRunId(childId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType()))).isFalse();
    }

    private Project newProjectWithResource(String name) {
        Project project = projectService.createProject(name + "-" + UUID.randomUUID());
        projectIds.add(project.id());
        commandService.attachResource(project.id(), project.activeRouteId(), null,
                "TEXT", Map.of("text", "continuation chain resource text"));
        // A durable KNOWLEDGE node in the lineage: the failed-capability
        // fixture points its broken ref at this node (a real allowed ref
        // that is not a resource, so the adapter persists FAILED).
        nodeService.createWorkspaceNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                com.specagent.node.NodeKind.KNOWLEDGE, "NOTE",
                Map.of("text", "continuation chain knowledge"),
                com.specagent.node.NodeAuthorKind.USER,
                com.specagent.node.KnowledgeStatus.PROPOSED);
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId(),
                "continuation chain root question?", null, List.of(), true);
        return projectService.getProject(project.id()).orElseThrow();
    }

    private Project newProjectWithoutResource(String name) {
        // Intentionally an empty route: the production fake brain drafts the
        // root question itself, which the executor persists as INTERACTION.
        Project project = projectService.createProject(name + "-" + UUID.randomUUID());
        projectIds.add(project.id());
        return project;
    }

    private void withMaxCycles(int maxCycles) {
        if (configuredMaxCycles < 0) {
            configuredMaxCycles = loopProperties.getMaxCycles();
        }
        loopProperties.setMaxCycles(maxCycles);
    }

    private AgentRun claim(AgentRun enqueued) {
        return runService.claimDecisionCycleRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + enqueued.id()));
    }

    private void stubDecisionsAfterFirstWithTerminalRespond(AtomicInteger decisions) {
        Answer<Object> forward = invocation -> {
            if (decisions.getAndIncrement() == 0) {
                return invocation.callRealMethod();
            }
            return terminalRespond(invocation.getArgument(0));
        };
        Mockito.doAnswer(forward).when(decisionEngine).runDecision(any());
    }

    private AgentResponseEnvelope terminalRespond(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        AgentResponseEnvelope response = new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("Continuation observed prior facts."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("RESPOND_TO_USER",
                        Map.of("message", "chain observed and done"),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
        return response;
    }

    private AgentResponseEnvelope createNoteProposal(AgentRequestEnvelope request,
                                                     String text) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A durable note is worth keeping."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "NOTE",
                                "content", Map.of("text", text)),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope createDecisionNodeProposal(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A decision needs confirmation."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "DECISION",
                                "content", Map.of("text", "decide the scope boundary")),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope updateNodeProposal(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("An update has no runtime path."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("UPDATE_NODE",
                        Map.of("nodeRef", "node:" + UUID.randomUUID()),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }

    private AgentResponseEnvelope brokenCapabilityInvoke(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        // A real lineage node that is NOT a resource: passes the contract
        // validator (ref inside allowed refs) but fails inside the adapter,
        // persisting a FAILED capability result without crashing the run.
        String nodeRef = request.snapshot().lineage().stream()
                .filter(entry -> !"INTERACTION".equals(entry.node().kind())
                        && !"RESOURCE".equals(entry.node().kind()))
                .map(entry -> "node:" + entry.node().id())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "expected non-resource lineage nodes in the snapshot"));
        return new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION_V2,
                request.runId(), null,
                new ObservationView(List.of("A resource reference broke."),
                        List.of(), List.of(), List.of()),
                new ActionProposal("INVOKE_CAPABILITY",
                        Map.of("capabilityId", "resource.extract_text",
                                "arguments", Map.of("nodeRef", nodeRef)),
                        snapshotId, request.snapshot().contextHash(), List.of(),
                        UUID.randomUUID(), request.runId().toString(), List.of()),
                new UsageView(1, List.of()), Map.of());
    }
}
