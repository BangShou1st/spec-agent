package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.QuestionDraftFailureIntegrationTest;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 文件名:QuestionDraftFailureIntegrationTest.java
 *
 * 测试目标:问题草稿决策循环的失败路径集成测试。
 *
 * 刻意不加 {@code @Transactional}:这套加固的要点正是 FAILED 的 agent run 在外层
 * 循环失败之后仍可查询,而测试事务会掩盖回滚行为。失败注入在决策引擎端口——与生产中
 * 供应商或契约失败跨越的边界相同。
 */
@SpringBootTest
@ActiveProfiles("test")
class QuestionDraftFailureIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private com.specagent.agent.runtime.RunService runService;
    @Autowired
    private com.specagent.agent.runtime.RunWorker worker;
    @Autowired
    private com.specagent.workspace.node.NodeService nodeService;

    // 对真实确定性引擎做 spy:失败用例只覆写单个方法,其余行为保持生产逻辑。
    @SpyBean
    private AgentDecisionEngine decisionEngine;

    @Test
    void draftRunPersistsFailedRunWhenDecisionEngineThrows() {
        Project project = projectService.createProject("Failure project");
        doThrow(new IllegalStateException("brain exploded"))
                .when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));

        assertThatThrownBy(() -> draftDriver.draftQuestion(project.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brain exploded");

        assertFailedRun(project);
    }

    @Test
    void draftRunPersistsFailedRunWhenDecisionContractViolated() {
        Project project = projectService.createProject("Failure project");
        // runId 不匹配:响应不可能属于这个 run。
        doAnswer(invocation -> new AgentResponseEnvelope(
                        "agent-decision.v2",
                        java.util.UUID.randomUUID(),
                        null,
                        new com.specagent.agent.protocol.ObservationView(
                                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                                java.util.List.of()),
                        null,
                        new com.specagent.agent.protocol.UsageView(1, java.util.List.of()),
                        java.util.Map.of()))
                .when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));

        assertThatThrownBy(() -> draftDriver.draftQuestion(project.id()))
                .isInstanceOf(com.specagent.agent.protocol.AgentContractException.class);

        assertFailedRun(project);
    }

    /**
     * 排队中的草稿 run,若执行前其记录的 tip 已被推进,必须 fail closed:
     * 不出现多余节点,失败的 run 保持可查询。
     */
    @Test
    void staleDraftTargetFailsClosed() {
        Project project = projectService.createProject("Stale draft project");
        AgentRun first = draftDriver.draftQuestion(project.id());
        assertThat(first.status()).isEqualTo(AgentRunStatus.COMPLETED);
        UUID activeRouteId = routeService.getRoute(project.activeRouteId()).orElseThrow().id();
        UUID recordedTip = routeService.getRoute(activeRouteId).orElseThrow().tipNodeId();

        AgentRun stale = runService.createQueuedDraftQuestion(project.id());
        // 另一个写入者在 worker 领取这个过期 run 之前向 tip 追加了节点,
        // 使其记录的 tip 不再是最新的。
        com.specagent.workspace.node.Node later = nodeService.createChildNode(
                project.id(), activeRouteId, recordedTip,
                "A later question", null, java.util.List.of(), true);

        var claimed = runService.claimDecisionCycleRun(stale.id())
                .orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer the active route tip");

        assertThat(runService.getRun(stale.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        // 只存在完成的草稿加上手动追加的节点;过期 run 什么也没产出。
        assertThat(agentRunService.listByProject(project.id())).hasSize(2);
        assertThat(nodeService.getNode(later.id())).isPresent();
    }

    private void assertFailedRun(Project project) {
        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
        AgentRun run = agentRunService.listByProject(project.id()).get(0);

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.contextSnapshotId()).isNotNull();
        assertThat(run.producedNodeId()).isNull();
        assertThat(run.producedAnswerId()).isNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedSpecSnapshotId()).isNull();

        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isNull();
    }
}
