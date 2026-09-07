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
        // converge on the same persisted child, never a second row.
        worker.executeRun(runService.getRun(root.id()).orElseThrow());

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
