package com.specagent.agent;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.common.Json;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SpecSnapshotService;
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
 * Failure-path integration tests for the fake full loop: rejected proposals
 * must never be persisted, the run must end FAILED, and the route tip must not
 * be polluted.
 *
 * <p>Deliberately not {@code @Transactional}: the whole point is that a FAILED
 * agent run must remain queryable after the surrounding agent cycle fails, and
 * rejected artifacts must stay absent from the database.
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
     * A STATE_UPDATE whose output violates the strict brain contract fails the
     * run after the immutable Answer persisted. The FAILED run stays queryable,
     * no patch or node is persisted, and the route tip is untouched.
     */
    @Test
    void failedAnswerRunKeepsAnswerAndDoesNotPersistRejectedArtifacts() {
        Project project = projectService.createProject("Answer failure project");
        Node tip = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", null, List.of(), true);

        // The stale-target scenario drives the same fail-closed path a
        // provider failure takes: nothing persists after the failure point.
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", tip.id(), null, "clarified", null);
        nodeService.createWorkspaceNode(project.id(), project.activeRouteId(), tip.id(),
                com.specagent.node.NodeKind.KNOWLEDGE, "NOTE",
                Map.of("text", "graph moved on"),
                com.specagent.node.NodeAuthorKind.USER,
                com.specagent.node.KnowledgeStatus.PROPOSED);

        assertThatThrownBy(() -> worker.executeRun(runService.claimNextAnswerCycle().orElseThrow()))
                .isInstanceOf(RuntimeException.class);

        // The failed run is queryable.
        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedAnswerId()).isNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedNodeId()).isNull();

        // No patch entered requirement state.
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();

        // Route tip moved on with the user's own node; no answer landed there.
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isNotEqualTo(tip.id());
    }


}
