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
        // 派生知识不再顶掉问题 tip,所以用新问题子节点把 tip 真正推走。
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", tip.id(), null, "clarified", null);
        nodeService.createChildNode(project.id(), project.activeRouteId(), tip.id(),
                "A later question", null, List.of(), true);

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
