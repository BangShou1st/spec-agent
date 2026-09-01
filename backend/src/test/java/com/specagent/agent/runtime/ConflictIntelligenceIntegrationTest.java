package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.ProposedClaim;
import com.specagent.agent.contract.StateUpdateResult;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Conflict-intelligence closure: STATE_UPDATE persists the new patch first,
 * and the immediately-following DECISION must read a post-state snapshot that
 * includes that patch/effective conflict claim. This keeps the answer cycle at
 * exactly two model calls while making STATE_UPDATE causally visible to
 * DECISION.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConflictIntelligenceIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;

    @MockBean
    private AgentDecisionEngine decisionEngine;

    private Project project;
    private Route route;
    private Node rootQuestion;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("冲突智能闭环-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootQuestion = nodeService.createRootNode(
                project.id(), route.id(), "是否一次性交付全部功能？", null, List.of(), true);
    }

    @Test
    void decisionReadsConflictClaimPersistedByStateUpdateInSameAnswerCycle() {
        AtomicReference<AgentRequestEnvelope> decisionRequest = new AtomicReference<>();

        when(decisionEngine.runStateUpdate(any(AgentRequestEnvelope.class)))
                .thenAnswer(invocation -> {
                    AgentRequestEnvelope request = invocation.getArgument(0);
                    return new AgentResponseEnvelope(
                            AgentProtocol.DECISION_PROTOCOL_VERSION,
                            request.runId(),
                            new StateUpdateResult(List.of(new ProposedClaim(
                                    "conflict",
                                    "一次性交付全部功能与仅有一名兼职开发者的资源约束互斥。",
                                    "unresolved",
                                    0.95,
                                    List.of()))),
                            null,
                            null,
                            new UsageView(1, List.of()),
                            Map.of());
                });

        when(decisionEngine.runDecision(any(AgentRequestEnvelope.class)))
                .thenAnswer(invocation -> {
                    AgentRequestEnvelope request = invocation.getArgument(0);
                    decisionRequest.set(request);
                    AgentInputSnapshot snapshot = request.snapshot();
                    return new AgentResponseEnvelope(
                            AgentProtocol.DECISION_PROTOCOL_VERSION,
                            request.runId(),
                            null,
                            new ObservationView(
                                    List.of(),
                                    List.of(),
                                    List.of("一次性交付范围与开发资源约束互斥。"),
                                    List.of()),
                            new ActionProposal(
                                    "REQUEST_USER_INPUT",
                                    Map.of(
                                            "questionText", "优先缩小范围还是增加开发资源？",
                                            "purpose", "解决当前互斥约束。",
                                            "options", List.of(
                                                    Map.of("label", "缩小范围"),
                                                    Map.of("label", "增加资源")),
                                            "allowFreeAnswer", true),
                                    UUID.fromString(snapshot.snapshotId()),
                                    snapshot.contextHash(),
                                    List.of(),
                                    UUID.randomUUID(),
                                    request.runId().toString(),
                                    List.of()),
                            new UsageView(1, List.of()),
                            Map.of());
                });

        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootQuestion.id(),
                null, "全部功能都要首版上线，但目前只有一名兼职开发者。", null);
        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        AgentRun completed = agentRunService.getRun(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);

        AgentRequestEnvelope captured = decisionRequest.get();
        assertThat(captured).isNotNull();
        assertThat(captured.snapshot().effectiveClaims())
                .anySatisfy(claim -> {
                    assertThat(claim.kind()).isEqualTo("conflict");
                    assertThat(claim.status()).isEqualTo("unresolved");
                    assertThat(claim.text()).contains("互斥");
                });
        assertThat(captured.snapshot().lineage())
                .flatExtracting(entry -> entry.patches())
                .flatExtracting(patch -> patch.claims())
                .anySatisfy(claim -> {
                    assertThat(claim.kind()).isEqualTo("conflict");
                    assertThat(claim.status()).isEqualTo("unresolved");
                });
    }
}
