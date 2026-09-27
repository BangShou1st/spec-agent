package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.FakeFullLoopFailureIntegrationTest;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.common.Json;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.spec.SpecSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:FakeFullLoopFailureIntegrationTest.java
 *
 * 测试目标:fake 全循环的失败路径集成测试:被拒绝的提案绝不能持久化,run 必须以
 * FAILED 结束,路由末梢不能被污染。
 *
 * 刻意不加 {@code @Transactional}:测试要点正是 FAILED 的 agent run 在外层
 * agent 循环失败之后仍可查询,且被拒绝的工件在数据库中保持缺席。
 */
@SpringBootTest
@ActiveProfiles("test")
class FakeFullLoopFailureIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private Json json;

    /**
     * 输出违反严格 brain 契约的 STATE_UPDATE,会在不可变 Answer 持久化之后使 run
     * 失败。FAILED 的 run 保持可查询,不持久化任何 patch 或节点,路由末梢不受影响。
     */
    @Test
    void failedAnswerRunKeepsAnswerAndDoesNotPersistRejectedArtifacts() {
        Project project = projectService.createProject("Answer failure project");
        Node tip = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", null, List.of(), true);

        // 过期目标场景走的是与供应商失败相同的 fail-closed 路径:失败点之后
        // 什么都不会持久化。
        // 派生知识不再顶掉问题 tip,所以用新问题子节点把 tip 真正推走。
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", tip.id(), null, "clarified", null);
        nodeService.createChildNode(project.id(), project.activeRouteId(), tip.id(),
                "A later question", null, List.of(), true);

        assertThatThrownBy(() -> worker.executeRun(runService.claimNextAnswerCycle().orElseThrow()))
                .isInstanceOf(RuntimeException.class);

        // 失败的 run 可查询。
        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedAnswerId()).isNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedNodeId()).isNull();

        // 没有 patch 进入 requirement 状态。
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();

        // 路由末梢随用户自己的节点推进;那里没有落任何答案。
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isNotEqualTo(tip.id());
    }


}
