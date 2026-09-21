package com.specagent.agent;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
 * Deterministic regression for the "ScenarioRunner / test driver vs brain
 * worker" claim race (tech-debt #13).
 *
 * <p>The ANSWER_CYCLE queue is shared: the production {@code RunWorker} poller,
 * the eval {@code ScenarioRunner} and every fixture in the same database draw
 * from it. A consumer that enqueues a run and then claims "the oldest queued
 * run" ({@code claimNextAnswerCycle}) can therefore be handed a DIFFERENT run —
 * it executes somebody else's work and leaves its own run queued, or reports an
 * empty queue while its own run sits behind another test's ordering. That is
 * not a scheduling coincidence: claiming the run you enqueued is the only
 * correct contract, and it is what these tests pin down.
 *
 * <p>These tests use only pre-existing seams (the shared
 * {@link AnswerCycleTestDriver}, {@link RunService#getRun}), so they reproduce
 * the defect before the driver claims by id and stay green afterwards — no
 * sleeps, no retries, no reliance on thread timing.
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
     * The regression: an older, unrelated ANSWER_CYCLE run is the queue head.
     * The driver must still execute exactly the run it enqueued and must leave
     * the foreign run untouched.
     */
    @Test
    void driverClaimsExactlyTheRunItEnqueuedWhenAnOlderForeignRunIsQueued() {
        Project mine = newProject("claim-owner");
        Node mineRoot = newRootNode(mine, "我最重要的目标是什么？");

        Project foreign = newProject("claim-foreign");
        Node foreignRoot = newRootNode(foreign, "外部夹具的问题？");
        UUID foreignRun = answerDriver.enqueueOnly(
                foreign.id(), "ANSWER_TIP", foreignRoot.id(), null, "foreign answer", null);
        // Deterministically make the foreign run the queue head, so a
        // queue-wide claim would hand it to this driver.
        jdbcTemplate.update(
                "UPDATE agent_runs SET created_at = now() - interval '1 hour' WHERE id = ?",
                foreignRun);

        var submitted = answerDriver.submitFreeText(mine.id(), "我的回答");

        // The driver executed the run it enqueued, not the foreign queue head.
        assertThat(submitted.run().id())
                .as("driver must execute exactly the run it enqueued")
                .isNotEqualTo(foreignRun);
        assertThat(submitted.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(submitted.run().inputNodeId()).isEqualTo(mineRoot.id());

        // The foreign run is neither executed nor claimed: it is still queued.
        AgentRun foreignAfter = runService.getRun(foreignRun).orElseThrow();
        assertThat(foreignAfter.status())
                .as("a foreign queued run must not be claimed or executed")
                .isEqualTo(AgentRunStatus.CREATED);
    }

    /**
     * Ownership is single-winner: once the worker has claimed a run, nobody can
     * claim it again and the worker refuses to re-execute a terminal row. A
     * duplicate delivery therefore never produces a second execution.
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

        // No second consumer can take the run...
        assertThat(runService.claimAnswerCycleRun(runId))
                .as("an already-claimed run is not claimable again")
                .isEmpty();
        // ...and a duplicate delivery fails closed instead of re-executing it.
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
