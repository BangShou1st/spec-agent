package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.ProposedClaim;
import com.specagent.agent.protocol.StateUpdateResult;
import com.specagent.agent.protocol.UsageView;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.BrainFailureCode;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:TypedRunFailureIntegrationTest.java
 *
 * 测试目标:失败的答题循环必须报告<em>具体是哪种</em>原因,并始终留下可恢复的
 * 检查点。
 *
 * 针对验收运行链的确定性回归:模型输出被 brain 契约拒绝,run 以一个不透明原因
 * 失败,重试唯一能做的就是续跑同一个持久化答案。本测试固定:原因是类型化的、
 * 确定性契约失败不会被自动重试、续跑路径复用既有 Answer 和 patch 而不创建任何一个
 * 的第二份。
 */
@SpringBootTest
@ActiveProfiles("test")
// 刻意不加 @Transactional:持久的 run 失败记录在它自己的 REQUIRES_NEW 事务中写入,
// 与生产完全一致;外层测试事务会掩盖本套件要断言的那条记录。
class TypedRunFailureIntegrationTest {

    @TestConfiguration
    static class TypedFailureEngineConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        TypedFailureEngine typedFailureEngine() {
            return new TypedFailureEngine();
        }
    }

    /** 确定性引擎:可以让 DECISION 以类型化的 brain 码失败。 */
    static class TypedFailureEngine implements AgentDecisionEngine {

        final AtomicInteger stateUpdates = new AtomicInteger();
        final AtomicInteger decisions = new AtomicInteger();
        volatile boolean failDecision = true;
        volatile BrainFailureCode decisionFailureCode = BrainFailureCode.MODEL_CONTRACT_VIOLATION;

        @Override
        public com.specagent.agent.protocol.AgentArtifactResponse runArtifactGeneration(
                AgentRequestEnvelope request) {
            throw new UnsupportedOperationException("not scripted for artifact generation");
        }

        @Override
        public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
            stateUpdates.incrementAndGet();
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    new StateUpdateResult(List.of(new ProposedClaim(
                            "goal", "The user clarified the outcome.", "confirmed",
                            0.9, List.of()))),
                    null, null,
                    new UsageView(1, List.of()),
                    Map.of());
        }

        @Override
        public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
            decisions.incrementAndGet();
            if (failDecision) {
                throw new AgentBrainUnavailableException(
                        "model output violates the DECISION contract",
                        new IllegalStateException("scripted contract violation"),
                        decisionFailureCode);
            }
            UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    null,
                    new ObservationView(List.of("known"), List.of(), List.of(), List.of()),
                    new ActionProposal(
                            "REQUEST_USER_INPUT",
                            Map.of(
                                    "questionText", "What is the next most important outcome?",
                                    "options", List.of(Map.of("label", "Clarify the goal")),
                                    "allowFreeAnswer", true),
                            snapshotId,
                            request.snapshot().contextHash(),
                            List.of(),
                            UUID.randomUUID(),
                            request.runId().toString(),
                            List.of()),
                    new UsageView(1, List.of()),
                    Map.of());
        }
    }

    @Autowired
    private TypedFailureEngine engine;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private com.specagent.workspace.spec.SpecSnapshotService specSnapshotService;
    @Autowired
    private AgentRunEventService eventService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker runWorker;
    @Autowired
    private RouteService routeService;

    @BeforeEach
    void resetEngine() {
        engine.failDecision = true;
        engine.decisionFailureCode = BrainFailureCode.MODEL_CONTRACT_VIOLATION;
        engine.stateUpdates.set(0);
        engine.decisions.set(0);
    }

    private Map<String, Object> runFailure(AgentRun run) {
        return eventService.findByRunId(run.id()).stream()
                .filter(event -> "RUN_FAILED".equals(event.eventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("run never recorded RUN_FAILED"))
                .payload();
    }

    private record Fixture(Project project, Node root) {
    }

    private Fixture fixture(String title) {
        Project project = projectService.createProject(title);
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters?", null, List.of(), true);
        return new Fixture(project, root);
    }

    /**
     * 入队一个答题 run 并通过 worker 驱动,容忍预期的脚本化失败,
     * 以便检查持久化的 run 记录。
     */
    private AgentRun submitExpectingFailure(Project project, String freeText) {
        UUID tipNodeId = routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId();
        UUID runId = answerDriver.enqueueOnly(project.id(), "ANSWER_TIP", tipNodeId,
                null, freeText, null);
        try {
            runWorker.executeRun(runService.claimAnswerCycleRun(runId).orElseThrow());
        } catch (RuntimeException expected) {
            // RunWorker 记录类型化失败后重新抛出。
        }
        return runService.getRun(runId).orElseThrow();
    }

    @Test
    void contractFailureIsTypedAndTheRetryReusesTheSameCheckpoint() {
        Fixture fixture = fixture("Typed contract failure");
        Project project = fixture.project();
        Node root = fixture.root();

        AgentRun first = submitExpectingFailure(project, "the durable answer");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        // brain 失败发生在 STATE_UPDATE 检查点落地之后。
        assertThat(engine.stateUpdates.get()).isEqualTo(1);
        // 确定性契约失败不会被自动重试。
        assertThat(engine.decisions.get()).isEqualTo(1);

        Map<String, Object> failure = runFailure(first);
        assertThat(failure)
                .containsEntry("reason", "model_contract_violation")
                .containsEntry("errorCode", "model_contract_violation");
        assertThat((String) failure.get("summary")).isNotBlank();

        // 检查点完好:恰好一个答案和一个 patch。
        Answer persisted = answerService.findAnswerForNode(project.activeRouteId(), root.id())
                .orElseThrow();
        assertThat(answerPatchService.findBySourceAnswerId(persisted.id())).isPresent();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);

        // 恢复续跑同一个答案并完成,不复制任何持久化记录。
        engine.failDecision = false;
        AnswerCycleTestDriver.SubmittedAnswer repaired =
                answerDriver.resumeAnswer(project.id(), persisted.id());

        assertThat(repaired.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(repaired.run().producedAnswerId()).isEqualTo(persisted.id());
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(root.id()))).hasSize(1);
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);
        assertThat(engine.decisions.get()).isEqualTo(2);
    }

    @Test
    void queuedArtifactRunFailsClosedOnAnUnprocessedTipAnswer() {
        Fixture fixture = fixture("Queued artifact gate");
        Project project = fixture.project();
        UUID tipNodeId = routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId();

        // 在 tip 尚未回答时排队工件 run……
        UUID runId = runService.createQueuedArtifactGeneration(project.id(), null, "gate", null)
                .id();
        // ……随后用户回答了,而该答案的 STATE_UPDATE 始终没有落地。
        answerService.finalizeAnswer(project.id(), project.activeRouteId(), tipNodeId,
                null, "saved answer", "user");

        try {
            runWorker.executeRun(runService.claimNextArtifact().orElseThrow());
        } catch (RuntimeException expected) {
            // 门禁在任何模型调用之前拒绝。
        }

        AgentRun run = runService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(run))
                .containsEntry("reason", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE)
                .containsEntry("errorCode", RunFailureReasons.ANSWER_CYCLE_INCOMPLETE);
        // 没有从不完整状态派生任何工件,也没有为它发起任何模型调用。
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
        assertThat(engine.decisions.get()).isZero();
    }

    @Test
    void timeoutIsReportedAsATimeoutNotAsAnUnavailableBrain() {
        Fixture fixture = fixture("Typed timeout failure");
        engine.decisionFailureCode = BrainFailureCode.BRAIN_TIMEOUT;

        AgentRun first = submitExpectingFailure(fixture.project(), "answer under timeout");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "brain_timeout")
                .containsEntry("errorCode", "brain_timeout");
    }

    @Test
    void ungroundedReferenceIsReportedAsABlockedCitation() {
        Fixture fixture = fixture("Typed ungrounded failure");
        engine.decisionFailureCode = BrainFailureCode.MODEL_UNGROUNDED_REFERENCE;

        AgentRun first = submitExpectingFailure(fixture.project(), "answer with foreign ref");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "model_ungrounded_reference")
                .containsEntry("errorCode", "model_ungrounded_reference");
    }

    @Test
    void providerFailureIsReportedAsAProviderFailure() {
        Fixture fixture = fixture("Typed provider failure");
        engine.decisionFailureCode = BrainFailureCode.MODEL_PROVIDER_FAILURE;

        AgentRun first = submitExpectingFailure(fixture.project(),
                "answer under provider failure");

        assertThat(first.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFailure(first))
                .containsEntry("reason", "model_provider_failure")
                .containsEntry("errorCode", "model_provider_failure");
    }
}
