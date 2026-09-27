package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.ProposedClaim;
import com.specagent.agent.protocol.StateUpdateResult;
import com.specagent.agent.protocol.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件名:ConflictIntelligenceIntegrationTest.java
 *
 * 测试目标:冲突智能闭环:STATE_UPDATE 先持久化新 patch,紧随其后的 DECISION 必须
 * 读取包含该 patch/有效冲突声明的状态后快照。修复/续跑必须复用既有 patch 而不再执行
 * STATE_UPDATE,同时保持相同的状态后 DECISION 语义。
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
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;

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

        stubConflictResolutionDecision(decisionRequest);

        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootQuestion.id(),
                null, "全部功能都要首版上线，但目前只有一名兼职开发者。", null);
        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        AgentRun completed = agentRunService.getRun(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);

        assertConflictVisibleToDecision(decisionRequest.get());
    }

    @Test
    void resumeReusesPersistedConflictPatchWithoutStateUpdateAndDecisionStillSeesConflict() {
        Answer answer = answerService.finalizeAnswer(
                project.id(), route.id(), rootQuestion.id(), null,
                "全部功能都要首版上线，但目前只有一名兼职开发者。", "user");
        answerPatchService.save(
                project.id(), route.id(), rootQuestion.id(), answer.id(),
                List.of(Claim.of(
                        ClaimKind.CONFLICT,
                        "一次性交付全部功能与仅有一名兼职开发者的资源约束互斥。",
                        ClaimStatus.UNRESOLVED,
                        null,
                        null)),
                null);

        AtomicReference<AgentRequestEnvelope> decisionRequest = new AtomicReference<>();
        stubConflictResolutionDecision(decisionRequest);

        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootQuestion.id(),
                null, null, answer.id());
        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        AgentRun completed = agentRunService.getRun(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        verify(decisionEngine, never()).runStateUpdate(any(AgentRequestEnvelope.class));
        assertConflictVisibleToDecision(decisionRequest.get());
    }

    private void stubConflictResolutionDecision(
            AtomicReference<AgentRequestEnvelope> decisionRequest) {
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
    }

    private void assertConflictVisibleToDecision(AgentRequestEnvelope captured) {
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
