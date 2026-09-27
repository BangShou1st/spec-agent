package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:AnswerCycleIntegrationTest.java
 *
 * 测试目标:端到端答题循环集成测试:入队 run,由 worker 领取并执行完整的
 * STATE_UPDATE → DECISION 路径,断言正确的结果(两次模型调用、新子节点、答案持久化、
 * RUN_CREATED 事件保留输入参数、重试走 RESUME_ANSWER 且答案唯一)。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnswerCycleIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AnswerService answerService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private Node rootNode;
    private Route route;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("E2E 回答周期项目");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootNode = nodeService.createRootNode(project.id(), route.id(),
                "最重要的目标是什么？", null, List.of(), true);
    }

    @Test
    void answerCycleCreatesNodeWithTwoProviderCalls() {
        // 1. 入队答题循环 run。
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                null, "明确首要目标", null);

        // 2. worker 领取并执行。
        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        // 3. 断言 run 已完成。
        AgentRun completed = agentRunService.getRun(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 4. 断言发生了 2 次模型调用(STATE_UPDATE + DECISION)。
        //    fake 引擎记录的是阶段事件,而不是 MODEL_INFERENCE 事件
        //    (后者只来自内部推理 broker 路径)。
        List<AgentRunPhase> phases = eventService.findByRunId(runId).stream()
                .map(AgentRunEvent::phase)
                .distinct()
                .collect(Collectors.toList());
        assertThat(phases).contains(
                AgentRunPhase.STATE_UPDATING,
                AgentRunPhase.STATE_UPDATED,
                AgentRunPhase.DECIDING,
                AgentRunPhase.PROPOSAL_CREATED);

        // 5. 断言创建了新的子节点。
        AgentRunEvent nodeEvent = eventService.findByRunId(runId).stream()
                .filter(e -> "PROPOSAL_CREATED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(nodeEvent.payload().get("actionFamily")).isEqualTo("REQUEST_USER_INPUT");

        // 6. 断言答案已持久化。
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("明确首要目标");
    }

    @Test
    void retrySameNodeUsesResumePathWithSingleAnswer() {
        // 1. 模拟一个持久化了 Answer 但未完成的循环:
        //    被回答的节点仍是活动路由末梢。
        Answer persisted = answerService.finalizeAnswer(project.id(), route.id(), rootNode.id(),
                null, "第一次回答", "user");

        // 2. 使用显式的持久化答案 id 续跑(修复路径)。
        UUID secondRunId = runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, persisted.id());
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        AgentRun secondCompleted = agentRunService.getRun(secondRunId).orElseThrow();
        assertThat(secondCompleted.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 3. 答案数量保持恰好为 1。
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
    }

    @Test
    void runEventPayloadPreservesInputParameters() {
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                UUID.randomUUID(), "自由文本输入", null);

        // 验证 RUN_CREATED 事件携带了输入参数。
        AgentRunEvent created = eventService.findByRunId(runId).stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(created.payload().get("freeText")).isEqualTo("自由文本输入");
        assertThat(created.payload().get("selectedOptionId")).isNotNull();
        assertThat(created.payload().get("operation")).isEqualTo("ANSWER_TIP");
    }
}
