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
 * Failure-path integration tests for the question-draft decision cycle.
 *
 * <p>Deliberately not {@code @Transactional}: the whole point of this hardening
 * is that a FAILED agent run must remain queryable after the surrounding cycle
 * fails. A test transaction would hide rollback behavior. The failure is
 * injected at the decision-engine port — the same boundary a provider or
 * contract failure crosses in production.
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

    // Spy over the real deterministic engine: failure tests override single
    // methods, everything else keeps production behavior.
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
        // runId mismatch: the response can never belong to this run.
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
     * A queued draft run whose recorded tip moved on before execution fails
     * closed: no extra node appears and the failed run stays queryable.
     */
    @Test
    void staleDraftTargetFailsClosed() {
        Project project = projectService.createProject("Stale draft project");
        AgentRun first = draftDriver.draftQuestion(project.id());
        assertThat(first.status()).isEqualTo(AgentRunStatus.COMPLETED);
        UUID activeRouteId = routeService.getRoute(project.activeRouteId()).orElseThrow().id();
        UUID recordedTip = routeService.getRoute(activeRouteId).orElseThrow().tipNodeId();

        AgentRun stale = runService.createQueuedDraftQuestion(project.id());
        // Another writer appends at the tip before the worker claims the
        // stale run, so its recorded tip is no longer current.
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
        // Exactly the completed draft plus the manual append exist; the stale
        // run produced nothing.
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
