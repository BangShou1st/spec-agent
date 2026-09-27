package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.SideEffectClass;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.ProposedClaim;
import com.specagent.agent.protocol.StateUpdateResult;
import com.specagent.agent.protocol.UsageView;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AnswerResumeSemanticReplayIntegrationTest.java
 *
 * 测试目标:修复/续跑的语义重放保证:当答题循环在 Answer(以及可选的 patch 检查点)
 * 已持久化之后失败,重试——经由 {@code RESUME_ANSWER} 路由——必须从不可变的持久化
 * Answer 重建 DECISION/STATE_UPDATE 输入。第二次尝试的触发事件必须与首次用户提交
 * 语义一致(ANSWER_SUBMITTED + selectedOptionId + freeText + 来源节点),绝不能退化为
 * 无上下文的 CONTINUE,且重试绝不创建第二个 Answer 或第二个 patch。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnswerResumeSemanticReplayIntegrationTest {

    @TestConfiguration
    static class ScriptedEngineConfig {

        @Bean
        @org.springframework.context.annotation.Primary
        AgentDecisionEngine scriptedDecisionEngine() {
            return new ScriptedDecisionEngine();
        }

        @Bean
        CapabilityAdapter resumeDriftCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.resume-drift", "1",
                            "resume drift probe", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("marker", invocation.invocationKey()),
                            List.of(), Map.of(), List.of());
                }
            };
        }
    }

    /**
     * 确定性引擎:记录收到的每个 envelope,可被指定在第 N 次 STATE_UPDATE
     * 或 DECISION 调用时失败。
     */
    static class ScriptedDecisionEngine implements AgentDecisionEngine {

        final List<AgentRequestEnvelope> stateUpdates = new ArrayList<>();
        final List<AgentRequestEnvelope> decisions = new ArrayList<>();
        int failStateUpdateAt = -1;
        int failDecisionAt = -1;

        @Override
        public com.specagent.agent.protocol.AgentArtifactResponse runArtifactGeneration(
                com.specagent.agent.protocol.AgentRequestEnvelope request) {
            throw new UnsupportedOperationException("not scripted for artifact generation");
        }

        @Override
        public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
            stateUpdates.add(request);
            if (stateUpdates.size() == failStateUpdateAt) {
                throw new AgentBrainUnavailableException("scripted STATE_UPDATE failure",
                        new IllegalStateException("scripted"));
            }
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
            decisions.add(request);
            if (decisions.size() == failDecisionAt) {
                throw new AgentBrainUnavailableException("scripted DECISION failure",
                        new IllegalStateException("scripted"));
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
                                    "questionText", "What is the most important outcome?",
                                    "options", List.of(Map.of("label", "Clarify")),
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

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ScriptedDecisionEngine scriptedEngine;
    @Autowired private CapabilityRuntime capabilityRuntime;

    private Project project;
    private Route route;
    private Node rootNode;
    private UUID selectedOptionId;
    private String freeText;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("回答恢复语义测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootNode = nodeService.createRootNode(project.id(), route.id(),
                "最重要的目标是什么？", null, List.of(), true);
        // 仅自由文本提交:选项归节点所有,随机的选项 id 会在任何 Answer
        // 持久化之前被拒绝。
        selectedOptionId = null;
        freeText = "聚焦离线同步的冲突处理";
        scriptedEngine.stateUpdates.clear();
        scriptedEngine.decisions.clear();
        scriptedEngine.failStateUpdateAt = -1;
        scriptedEngine.failDecisionAt = -1;
    }

    @Test
    void resumeAfterDecisionFailureReplaysOriginalSubmissionSemantics() {
        // 1. 用选项 X + 自由文本 Y 提交;STATE_UPDATE 成功(patch 已持久化),
        //    第一次 DECISION 失败。
        scriptedEngine.failDecisionAt = 1;
        UUID firstRunId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        // 安全检查点幸存:恰好一个 Answer、恰好一个 patch。
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        UUID answerId = answers.get(0).id();
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();

        // 2. 重试路由到 RESUME_ANSWER,携带持久化的答案 id。
        UUID secondRunId = runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);
        assertThat(agentRunService.getRun(secondRunId).orElseThrow().status())
                .isEqualTo(com.specagent.agent.runtime.AgentRunStatus.COMPLETED);

        // 3. 续跑循环复用了持久化的 patch,而不是重新执行 STATE_UPDATE。
        assertThat(scriptedEngine.stateUpdates).as("STATE_UPDATE calls").hasSize(1);
        assertThat(eventService.findByRunId(secondRunId)).anySatisfy(event ->
                assertThat(event.eventType()).isEqualTo("STATE_UPDATE_SKIPPED"));

        // 4. 两个 DECISION envelope 语义等价。
        assertThat(scriptedEngine.decisions).hasSize(2);
        var firstEvent = scriptedEngine.decisions.get(0).event();
        var resumedEvent = scriptedEngine.decisions.get(1).event();
        assertThat(firstEvent.kind()).isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.kind())
                .as("resume must not degrade into a context-free CONTINUE")
                .isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(firstEvent.selectedOptionId());
        assertThat(resumedEvent.freeText()).isEqualTo(firstEvent.freeText());
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(firstEvent.anchorNodeId());
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(selectedOptionId);
        assertThat(resumedEvent.freeText()).isEqualTo(freeText);
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(rootNode.id());

        // 5. 重试之后没有重复的持久化工件。
        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();
        assertThat(allPatches()).hasSize(1);
    }

    @Test
    void resumeWithoutPersistedPatchRebuildsStateUpdateFromPersistedAnswer() {
        // 第一次尝试在 STATE_UPDATE 中失败:答案已存在,patch 尚无。
        scriptedEngine.failStateUpdateAt = 1;
        UUID firstRunId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answers.get(0).id())).isEmpty();

        // 续跑必须从持久化的 Answer 重建 STATE_UPDATE 输入,
        // 且仍然绝不创建第二个 Answer。
        UUID answerId = answers.get(0).id();
        runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        assertThat(scriptedEngine.stateUpdates).hasSize(2);
        var firstEvent = scriptedEngine.stateUpdates.get(0).event();
        var resumedEvent = scriptedEngine.stateUpdates.get(1).event();
        assertThat(resumedEvent.kind()).isEqualTo("ANSWER_SUBMITTED");
        assertThat(resumedEvent.selectedOptionId()).isEqualTo(firstEvent.selectedOptionId());
        assertThat(resumedEvent.freeText()).isEqualTo(firstEvent.freeText());
        assertThat(resumedEvent.anchorNodeId()).isEqualTo(rootNode.id());

        assertThat(scriptedEngine.decisions).hasSize(1);
        assertThat(scriptedEngine.decisions.get(0).event().kind())
                .isEqualTo("ANSWER_SUBMITTED");

        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(allPatches()).hasSize(1);
    }

    /**
     * T5——带活跃工作区漂移的修复:第一次尝试的 DECISION 失败后(状态后快照与
     * 冻结投影已持久化),一个能力结果落入了项目。RESUME_ANSWER 重试必须跳过
     * STATE_UPDATE、不创建第二个 Answer,并把原始的冻结状态后模型上下文交给
     * DECISION——相同的快照 id、相同的能力观察——而不是在已漂移的工作区上重建。
     */
    @Test
    void resumeReplaysOriginalFrozenPostStateDecisionInputDespiteLiveDrift() {
        // 1. 第一次尝试:STATE_UPDATE 持久化 patch,DECISION 失败。
        scriptedEngine.failDecisionAt = 1;
        runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                selectedOptionId, freeText, null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(firstClaimed))
                .isInstanceOf(RuntimeException.class);

        assertThat(scriptedEngine.decisions).hasSize(1);
        var originalDecisionEnvelope = scriptedEngine.decisions.get(0);
        UUID answerId = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id())).get(0).id();

        // 2. 失败之后的活跃漂移:新的能力结果对任何活跃重建都可见。
        capabilityRuntime.invoke("resume-drift-" + UUID.randomUUID(),
                "test.resume-drift", project.id(), null, Map.of());

        // 3. 通过 RESUME_ANSWER 修复。
        runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, answerId);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        // 4. STATE_UPDATE 没有重跑;DECISION 再次执行。
        assertThat(scriptedEngine.stateUpdates).as("no STATE_UPDATE rerun").hasSize(1);
        assertThat(scriptedEngine.decisions).hasSize(2);

        // 5. 续跑的 DECISION 收到的是原始的冻结状态后模型上下文:快照身份
        //    相同且载荷相同——漂移的能力观察缺席,与第一次尝试完全一致。
        var resumedEnvelope = scriptedEngine.decisions.get(1);
        assertThat(resumedEnvelope.snapshot()).isEqualTo(originalDecisionEnvelope.snapshot());
        assertThat(resumedEnvelope.snapshot().capabilityResults())
                .as("post-freeze capability drift must not enter the replayed input")
                .isEqualTo(originalDecisionEnvelope.snapshot().capabilityResults());

        // 6. 没有第二个 Answer,也没有第二个 patch。
        assertThat(answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()))).hasSize(1);
        assertThat(answerPatchService.findBySourceAnswerId(answerId)).isPresent();
    }

    private List<AnswerPatch> allPatches() {
        return answerPatchService.findBySourceAnswerId(rootNodeIdAnswers().get(0).id())
                .map(List::of).orElseGet(List::of);
    }

    private List<Answer> rootNodeIdAnswers() {
        return answerService.findAnswersForRouteAndNodeIds(route.id(), List.of(rootNode.id()));
    }
}
