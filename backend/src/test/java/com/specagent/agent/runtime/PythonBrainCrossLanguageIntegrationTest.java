package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文件名:PythonBrainCrossLanguageIntegrationTest.java
 *
 * 测试目标:Stage A 的跨语言出口门禁:真实的 Python agent-brain 服务经 Java 内部
 * 推理 broker 完成一次确定性 DECISION,且交换的响应与持久化的事件中不出现任何
 * 供应商密钥材料。
 *
 * 需要 broker 模式的 agent-brain 指向本进程的 broker URL——只有 broker 模式才会
 * 回拨 Java 推理 broker,fake 模型模式永远无法满足下面的 broker 调用断言。用跳过
 * 消息中打印的 broker URL 启动它:
 *
 * cd agent-brain &amp;&amp; SPEC_AGENT_BRAIN_MODEL_MODE=broker \
 *   SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:&lt;port&gt;/internal/v1/model-inference \
 *   .venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
 *
 * 测试把 Spring 绑定在隔离端口——默认使用操作系统分配的空闲端口,或在需要显式
 * 约定时用 {@code SPEC_AGENT_CROSS_LANG_PORT}——并行运行或残留进程永远不会在固定
 * 端口上冲突。生产 broker 的默认端口不受影响。brain 未运行时测试被跳过,
 * 默认套件离线保持绿色。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=${SPEC_AGENT_CROSS_LANG_PORT:0}",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=http://localhost:8100",
                // dev brain 以 SPEC_AGENT_BRAIN_INTERNAL_SECRET 启动
                // (见 start-dev.bat);这里出示同一密钥。可通过环境变量覆盖,
                // 与 application.yml 及评估套件保持一致。
                "spec.agent.brain.internal-secret=${SPEC_AGENT_BRAIN_INTERNAL_SECRET:dev-internal-secret}",
                "spec.agent.action-eligibility.mode=enforced"
        })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonBrainCrossLanguageIntegrationTest {

    private static final String BRAIN_HEALTH = "http://localhost:8100/health";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunEventRepository eventRepository;

    @BeforeAll
    void requireRunningBrain() {
        assumeTrue(brainReachable(),
                "agent-brain not reachable at " + BRAIN_HEALTH
                        + " — start it in broker mode with "
                        + "SPEC_AGENT_BRAIN_MODEL_MODE=broker "
                        + "SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:"
                        + serverPort + "/internal/v1/model-inference");
    }

    @Test
    void pythonCompletesDeterministicDecisionThroughJavaBroker() {
        Project project = projectService.createProject("跨语言决策项目");

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        AgentRun claimed = runService.claimDecisionCycleRun(run.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + run.id()));
        worker.executeRun(claimed);

        assertThat(runService.getRun(run.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentRunEvent> events = eventRepository.findByRunId(run.id());

        // worker 生命周期事件记录精确的阶段推进。展示类事件不属于生命周期
        // 契约:MODEL_INFERENCE 是 broker 线上细节(下面单独断言),
        // PROCESS_NOTE 是 RunProgressRecorder 发出的用户可读进度注记。
        // 纯续跑是一次 DECISION——该路径上没有 STATE_UPDATE 阶段。
        List<String> lifecycle = events.stream()
                .filter(event -> !event.eventType().equals("MODEL_INFERENCE"))
                .filter(event -> !event.eventType().equals("PROCESS_NOTE"))
                .map(AgentRunEvent::eventType).toList();
        assertThat(lifecycle).containsExactly(
                "RUN_CREATED",
                "SNAPSHOT_BUILT",
                "DECISION_STARTED",
                "PROPOSAL_CREATED",
                "EXECUTING",
                "RUN_COMPLETED");
        assertThat(events.stream().map(AgentRunEvent::phase).distinct().toList())
                .containsExactly(
                        AgentRunPhase.CREATED,
                        AgentRunPhase.SNAPSHOT_BUILT,
                        AgentRunPhase.DECIDING,
                        AgentRunPhase.PROPOSAL_CREATED,
                        AgentRunPhase.EXECUTING,
                        AgentRunPhase.COMPLETED);

        // brain 调用跨越了 Java 推理 broker。
        List<String> brokerCallTypes = events.stream()
                .filter(event -> event.eventType().equals("MODEL_INFERENCE"))
                .map(event -> (String) event.payload().get("callType")).toList();
        assertThat(brokerCallTypes).containsExactly("DECISION");

        // 提案经完整远程路径返回、通过校验,并作为 tip 处的真实问题节点
        // 自动执行。
        String proposalText = events.stream()
                .filter(event -> event.eventType().equals("PROPOSAL_CREATED"))
                .map(event -> event.payload().toString())
                .findFirst().orElse("");
        assertThat(proposalText).contains(ActionFamily.REQUEST_USER_INPUT.name());
        assertThat(runService.getRun(run.id()).orElseThrow().producedNodeId()).isNotNull();

        // 任何持久化的事件载荷中零供应商密钥材料。
        String allPayloads = events.stream()
                .map(event -> event.payload().toString())
                .reduce("", (a, b) -> a + b);
        assertThat(allPayloads).doesNotContain("sk-").doesNotContain("apiKey");
    }

    /**
     * 问题草稿变成单次 DECISION 续跑之后,答题循环的 STATE_UPDATE 仍跨越同一个
     * broker,使跨语言门禁继续覆盖两种 brain 调用类型。
     */
    @Test
    void pythonAnswerCycleCrossesTheBrokerWithStateUpdateAndDecision() {
        Project project = projectService.createProject("跨语言回答项目");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "最重要的目标是什么？", null, List.of(), true);

        UUID answerRunId = answerDriver.submitFreeText(project.id(), "明确首要目标")
                .run().id();
        assertThat(runService.getRun(answerRunId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentRunEvent> events = eventRepository.findByRunId(answerRunId);
        List<String> brokerCallTypes = events.stream()
                .filter(event -> event.eventType().equals("MODEL_INFERENCE"))
                .map(event -> event.payload().get("callType").toString()).toList();
        assertThat(brokerCallTypes).containsExactly("STATE_UPDATE", "DECISION");
        // 被回答的根节点保持原位:恰好一个血统节点被回答,
        // 循环产出了下一个问题节点。
        assertThat(runService.getRun(answerRunId).orElseThrow().producedNodeId()).isNotNull();
        assertThat(nodeService.getNode(runService.getRun(answerRunId).orElseThrow()
                .producedNodeId()).orElseThrow().parentNodeId()).isEqualTo(root.id());
    }

    private boolean brainReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(BRAIN_HEALTH))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }
}
