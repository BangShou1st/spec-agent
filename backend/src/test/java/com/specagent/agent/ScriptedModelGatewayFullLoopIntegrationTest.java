package com.specagent.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
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
    private FakeAgentOrchestrator fakeAgentOrchestrator;
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
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
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
        UUID queuedRunId = answerDriver.enqueueOnly(project.id(), "ANSWER_TIP",
                rootNode.id(), null, "clarified", null);
        nodeService.createWorkspaceNode(project.id(), project.activeRouteId(),
                rootNode.id(), com.specagent.node.NodeKind.KNOWLEDGE, "NOTE",
                Map.of("text", "A later question"),
                com.specagent.node.NodeAuthorKind.USER,
                com.specagent.node.KnowledgeStatus.PROPOSED);

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
     * resume against the already-completed cycle fails closed without
     * duplicating any of them. Once the tip advanced past the answered node,
     * the repair window is over — the resume guard rejects it.
     */
    @Test
    void completedCycleArtifactsSurviveAFailedFollowup() {
        project = projectService.createProject("Completed then failed");
        fakeAgentOrchestrator.draftNextQuestion(project.id());

        var first = answerDriver.submitFreeText(project.id(), "the clarified outcome");
        assertThat(first.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        UUID answeredNodeId = first.run().inputNodeId();
        assertThat(answeredNodeId).isNotNull();

        // The completed cycle persisted exactly one answer and one patch.
        assertThat(answerService.getAnswer(first.answerId())).isPresent();
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);

        // A follow-up resume against the now-stale answered node fails closed
        // without touching the completed cycle's artifacts.
        UUID followupRunId = answerDriver.enqueueOnly(project.id(), "RESUME_ANSWER",
                null, null, null, first.answerId());
        var followup = answerCycleClaim(followupRunId);
        assertThatThrownBy(() -> worker.executeRun(followup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the active route tip");
        AgentRun failed = agentRunService.getRun(followupRunId).orElseThrow();
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);

        // Exactly one answer and one patch remain.
        assertThat(answerRepository.findByRouteAndNodeIds(
                project.activeRouteId(), List.of(answeredNodeId))).hasSize(1);
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).hasSize(1);
    }

    private AgentRun answerCycleClaim(UUID expectedRunId) {
        return runService.claimNextAnswerCycle()
                .filter(run -> run.id().equals(expectedRunId))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued answer-cycle run " + expectedRunId));
    }
}
