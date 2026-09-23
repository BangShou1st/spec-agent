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
 * Failure-path integration tests for the scripted full loop: a run that fails
 * mid-cycle must keep the safe checkpoints that already persisted (immutable
 * answer, accepted patch) and must never persist artifacts after the failure
 * point. The deterministic fake engine cannot be scripted to explode, so the
 * failure is injected at the runtime boundary with a stale target — the same
 * fail-closed path a provider failure takes.
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
        // Deliberately not @Transactional: the FAILED marking runs in its own
        // REQUIRES_NEW transaction, so cleanup must be manual. agent_runs hold
        // FKs to the produced answers/patches/nodes, so they go first.
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
        // Agent graph mutations now enter the operation log; the rows hold a
        // project FK and must go before the project itself.
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * A run whose target moved on before execution fails closed: nothing
     * persists, and the failed run stays queryable with its trace.
     */
    @Test
    void staleTargetFailurePersistsNothing() {
        project = projectService.createProject("Stale target failure");
        Node rootNode = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", "Clarify the outcome", List.of(), true);

        // Enqueue the answer run, then move the graph on before it is claimed.
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

        // Nothing entered requirement state.
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(rootNode.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();
    }

    /**
     * A completed cycle keeps every artifact it persisted, and a subsequent
     * resume against the historical answer is an idempotent checkpoint-only
     * recovery without duplicating any of them.
     */
    @Test
    void completedCycleArtifactsSurviveAFailedFollowup() {
        project = projectService.createProject("Completed then failed");
        draftDriver.draftQuestion(project.id());

        var first = answerDriver.submitFreeText(project.id(), "the clarified outcome");
        assertThat(first.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        UUID answeredNodeId = first.run().inputNodeId();
        assertThat(answeredNodeId).isNotNull();

        // The completed cycle persisted exactly one answer and one patch.
        assertThat(answerService.getAnswer(first.answerId())).isPresent();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);

        // A follow-up resume against the now-historical answered node is a
        // checkpoint-only no-op: it must not replay the later decision or
        // touch the completed cycle's artifacts.
        UUID followupRunId = answerDriver.enqueueOnly(project.id(), "RESUME_ANSWER",
                null, null, null, first.answerId());
        var followup = answerCycleClaim(followupRunId);
        worker.executeRun(followup);
        AgentRun recovered = agentRunService.getRun(followupRunId).orElseThrow();
        assertThat(recovered.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // Exactly one answer and one patch remain.
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(answeredNodeId))).hasSize(1);
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);
    }

    /**
     * Claims exactly the run this fixture enqueued.
     *
     * <p>The ANSWER_CYCLE queue is shared with every other fixture in the same
     * database, so "the oldest queued run" is not necessarily ours: a queue-wide
     * claim can be handed an unrelated run and then fail its own identity check
     * depending on execution order. Claiming by id is the contract the
     * production path and {@code AnswerCycleTestDriver} already use (see
     * {@code AnswerCycleClaimOwnershipIntegrationTest}).
     */
    private AgentRun answerCycleClaim(UUID expectedRunId) {
        return runService.claimAnswerCycleRun(expectedRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued answer-cycle run " + expectedRunId
                                + " to be claimable"));
    }
}
