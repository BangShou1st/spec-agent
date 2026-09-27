package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.AnswerCycleClaimOwnershipIntegrationTest;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AnswerCycleClaimOwnershipIntegrationTest.java
 *
 * 测试目标:针对"ScenarioRunner / 测试驱动 vs brain worker"领取竞态的确定性回归
 * (tech-debt #13)。ANSWER_CYCLE 队列是共享的:生产 {@code RunWorker} 轮询器、评估
 * {@code ScenarioRunner} 以及同一数据库里的所有 fixture 都从中取任务。消费者入队后如果
 * 领取"最旧的排队 run"({@code claimNextAnswerCycle}),可能拿到别人的 run——执行了别人的
 * 工作、自己的 run 却仍留在队列中,或报告队列为空而自己的 run 排在别的测试后面。这不是
 * 调度巧合:"领取自己入队的 run"才是唯一正确的契约,这些测试将其固定下来。
 *
 * 测试只使用既有接缝(共享的 {@link AnswerCycleTestDriver}、
 * {@link RunService#getRun}),因此在驱动改为按 id 领取之前能复现缺陷、之后保持绿色——
 * 不依赖 sleep、重试或线程时序。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnswerCycleClaimOwnershipIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerCycleTestDriver answerDriver;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private com.specagent.agent.runevent.AgentRunEventService eventService;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 回归场景:一个更早的无关 ANSWER_CYCLE run 是队头。驱动必须仍然精确执行
     * 自己入队的那个 run,且不碰外来 run。
     */
    @Test
    void driverClaimsExactlyTheRunItEnqueuedWhenAnOlderForeignRunIsQueued() {
        Project mine = newProject("claim-owner");
        Node mineRoot = newRootNode(mine, "我最重要的目标是什么？");

        Project foreign = newProject("claim-foreign");
        Node foreignRoot = newRootNode(foreign, "外部夹具的问题？");
        UUID foreignRun = answerDriver.enqueueOnly(
                foreign.id(), "ANSWER_TIP", foreignRoot.id(), null, "foreign answer", null);
        // 确定性地把外来 run 变成队头,这样按队列领取就会把它交给本驱动。
        jdbcTemplate.update(
                "UPDATE agent_runs SET created_at = now() - interval '1 hour' WHERE id = ?",
                foreignRun);

        var submitted = answerDriver.submitFreeText(mine.id(), "我的回答");

        // 驱动执行的是自己入队的 run,而不是外来的队头。
        assertThat(submitted.run().id())
                .as("driver must execute exactly the run it enqueued")
                .isNotEqualTo(foreignRun);
        assertThat(submitted.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(submitted.run().inputNodeId()).isEqualTo(mineRoot.id());

        // 外来 run 未被执行也未被领取:仍处于排队状态。
        AgentRun foreignAfter = runService.getRun(foreignRun).orElseThrow();
        assertThat(foreignAfter.status())
                .as("a foreign queued run must not be claimed or executed")
                .isEqualTo(AgentRunStatus.CREATED);
    }

    /**
     * 所有权单赢家:worker 领取 run 之后,任何人都无法再次领取,且 worker 拒绝
     * 重复执行终态行。因此重复投递永远不会产生第二次执行。
     */
    @Test
    void aClaimedRunIsNeverClaimableOrExecutedTwice() {
        Project project = newProject("claim-once");
        Node root = newRootNode(project, "只能回答一次的问题？");
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", root.id(), null, "唯一一次回答", null);

        AgentRun claimed = runService.claimAnswerCycleRun(runId).orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // 其他消费者无法再领取该 run……
        assertThat(runService.claimAnswerCycleRun(runId))
                .as("an already-claimed run is not claimable again")
                .isEmpty();
        // ……且重复投递会失败(fail closed)而不是再次执行。
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only executes freshly claimed RUNNING runs");

        long completions = eventService.findByRunId(runId).stream()
                .filter(event -> "RUN_COMPLETED".equals(event.eventType()))
                .count();
        assertThat(completions).as("exactly one execution").isEqualTo(1);
    }

    private Project newProject(String prefix) {
        return projectService.createProject(prefix + "-" + UUID.randomUUID());
    }

    private Node newRootNode(Project project, String question) {
        return nodeService.createRootNode(
                project.id(), project.activeRouteId(), question, null, List.of(), true);
    }
}
