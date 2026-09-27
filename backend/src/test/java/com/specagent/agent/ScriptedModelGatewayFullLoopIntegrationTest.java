package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.ScriptedModelGatewayFullLoopIntegrationTest;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ScriptedModelGatewayFullLoopIntegrationTest.java
 *
 * 测试目标:脚本化全循环的失败路径集成测试:中途失败的 run 必须保留已持久化的
 * 安全检查点(不可变答案、已接受的 patch),且绝不在失败点之后持久化工件。确定性
 * fake 引擎无法被脚本化成崩溃,因此在运行时边界注入过期目标制造失败——与供应商
 * 失败走的是同一条 fail-closed 路径。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptedModelGatewayFullLoopIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private com.specagent.agent.runtime.RunWorker worker;
    @Autowired
    private com.specagent.agent.runtime.RunService runService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        // 刻意不加 @Transactional:FAILED 标记在独立的 REQUIRES_NEW 事务中执行,
        // 因此清理必须手动做。agent_runs 持有指向产出的 answers/patches/nodes
        // 的外键,所以要先删它们。
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
        // Agent 图变更现在会进入操作日志;这些行持有项目外键,
        // 必须在项目本身之前删除。
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * 目标在执行前已被推进的 run 必须 fail closed:什么都不持久化,
     * 失败的 run 连同其 trace 保持可查询。
     */
    @Test
    void staleTargetFailurePersistsNothing() {
        project = projectService.createProject("Stale target failure");
        Node rootNode = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", "Clarify the outcome", List.of(), true);

        // 先入队答题 run,然后在它被领取之前把图推进。
        // 派生知识节点不再顶掉问题 tip,所以用一个新问题子节点把 tip 真正
        // 推走,制造"run 的目标已不是路线 tip"的过期场景。
        UUID queuedRunId = answerDriver.enqueueOnly(project.id(), "ANSWER_TIP",
                rootNode.id(), null, "clarified", null);
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                rootNode.id(), "A later question", null, List.of(), true);

        var claimed = answerCycleClaim(queuedRunId);
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(RuntimeException.class);

        AgentRun run = agentRunService.getRun(queuedRunId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedAnswerId()).isNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedNodeId()).isNull();

        // 没有任何内容进入 requirement 状态。
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(rootNode.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();
    }

    /**
     * 完成的循环保留它持久化的每一个工件;对历史答案的后续 resume 是
     * 仅检查点的幂等恢复,不复制任何工件。
     */
    @Test
    void completedCycleArtifactsSurviveAFailedFollowup() {
        project = projectService.createProject("Completed then failed");
        draftDriver.draftQuestion(project.id());

        var first = answerDriver.submitFreeText(project.id(), "the clarified outcome");
        assertThat(first.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        UUID answeredNodeId = first.run().inputNodeId();
        assertThat(answeredNodeId).isNotNull();

        // 完成的循环恰好持久化了一个答案和一个 patch。
        assertThat(answerService.getAnswer(first.answerId())).isPresent();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);

        // 对已成为历史的已回答节点做后续 resume 是仅检查点的 no-op:
        // 不能重放后续决策,也不能碰已完成循环的工件。
        UUID followupRunId = answerDriver.enqueueOnly(project.id(), "RESUME_ANSWER",
                null, null, null, first.answerId());
        var followup = answerCycleClaim(followupRunId);
        worker.executeRun(followup);
        AgentRun recovered = agentRunService.getRun(followupRunId).orElseThrow();
        assertThat(recovered.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 恰好剩一个答案和一个 patch。
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(answeredNodeId))).hasSize(1);
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);
    }

    /**
     * 精确领取本 fixture 入队的 run。
     *
     * ANSWER_CYCLE 队列与同一数据库中的所有其他 fixture 共享,因此"最旧的
     * 排队 run"不一定是我们的:按队列领取可能拿到无关 run,再因身份校验失败而
     * 出错(取决于执行顺序)。按 id 领取是生产路径与 {@code AnswerCycleTestDriver}
     * 已经采用的契约(见 {@code AnswerCycleClaimOwnershipIntegrationTest})。
     */
    private AgentRun answerCycleClaim(UUID expectedRunId) {
        return runService.claimAnswerCycleRun(expectedRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued answer-cycle run " + expectedRunId
                                + " to be claimable"));
    }
}
