package com.specagent.capability;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.graph.GraphCommandService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:CapabilityAnswerCycleIntegrationTest.java
 *
 * 测试目标:验证完整回答周期中的能力调用链路——决策引擎基于可见的能力
 * 描述符提议 INVOKE_CAPABILITY,策略自动执行只读能力,调用恰好记录一次,
 * 且不会产生重复的图谱变更。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CapabilityAnswerCycleIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private Node questionNode;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("能力回答周期测试");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node resource = commandService.attachResource(
                project.id(), route.id(), null, "TEXT",
                Map.of("text", "客户访谈记录：核心诉求是离线可用。"));
        // 问题节点位于资源节点之后,使 RESOURCE 节点进入
        // 回答周期上下文的 lineage。
        questionNode = nodeService.createChildNode(
                project.id(), route.id(), resource.id(),
                "离线模式最重要的场景是什么？", null, List.of(), true);
    }

    @Test
    void answerCycleInvokesReadOnlyCapabilityExactlyOnce() {
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", questionNode.id(), null, "现场施工环境没有网络", null);

        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 只读能力恰好执行一次(幂等键由 run + proposal 派生,
        // 重试永远不会产生重复执行)。
        List<CapabilityInvocationRecord> invocations =
                invocationRepository.findRecentCompleted(project.id(), 10);
        assertThat(invocations).hasSize(1);
        CapabilityInvocationRecord invocation = invocations.get(0);
        assertThat(invocation.capabilityId()).isEqualTo(ResourceExtractTextCapability.CAPABILITY_ID);
        assertThat(invocation.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);

        // executing 阶段记录了能力族。
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType())
                        && "INVOKE_CAPABILITY".equals(String.valueOf(e.payload().get("actionFamily")))))
                .isTrue();
    }
}
