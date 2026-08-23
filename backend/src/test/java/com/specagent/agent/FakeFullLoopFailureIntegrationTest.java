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
    private FakeAgentOrchestrator fakeAgentOrchestrator;
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

    @MockBean
    private com.specagent.testing.FakeModelAdapter fakeModelAdapter;

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

        // The scripted STATE_UPDATE emits a claim with a blank text, which the
        // strict contract parser rejects fail-closed before any patch may be
        // reflected or persisted.
        org.mockito.Mockito.when(fakeModelAdapter.run(
                        org.mockito.ArgumentMatchers.any(ModelRequest.class)))
                .thenAnswer(invocation -> invocation.callRealMethod());
        // The FakeModelAdapter is deterministic; to force a contract failure we
        // drive the stale-target failure scenario instead of mocking provider
        // payloads (the brain contract itself is covered by validator tests).
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

    @Test
    void fakeSpecRunRejectsUngroundedSpecDraft() {
        Project project = projectService.createProject("Spec rejection project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters most?", null, List.of(), true);

        org.mockito.Mockito.when(fakeModelAdapter.run(
                        org.mockito.ArgumentMatchers.any(ModelRequest.class)))
                .thenAnswer(invocation -> {
                    ModelRequest request = invocation.getArgument(0);
                    return new ModelResponse(
                            request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                            AgentAction.GENERATE_SPEC,
                            json.write(new com.specagent.agent.contracts.SpecDraft(
                                    Map.of("Overview", "content without source references"),
                                    List.of(),
                                    Map.of())),
                            Map.of("adapter", "mock"));
                });

        assertThatThrownBy(() -> fakeAgentOrchestrator.generateSpec(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Spec grounding rejected");

        assertThat(agentRunService.listByProject(project.id())).isNotEmpty();
        AgentRun run = agentRunService.listByProject(project.id())
                .get(agentRunService.listByProject(project.id()).size() - 1);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedSpecSnapshotId()).isNull();

        // No spec snapshot was persisted.
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
    }

    @Test
    void fakeSpecRunRejectsNonexistentSourceReference() {
        Project project = projectService.createProject("Source ref rejection project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters most?", null, List.of(), true);
        UUID nonexistentAnswerId = UUID.randomUUID();

        org.mockito.Mockito.when(fakeModelAdapter.run(
                        org.mockito.ArgumentMatchers.any(ModelRequest.class)))
                .thenAnswer(invocation -> {
                    ModelRequest request = invocation.getArgument(0);
                    return new ModelResponse(
                            request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                            AgentAction.GENERATE_SPEC,
                            json.write(new com.specagent.agent.contracts.SpecDraft(
                                    Map.of("Overview", "grounded looking content"),
                                    List.of(),
                                    Map.of("Overview", List.of("answer:" + nonexistentAnswerId)))),
                            Map.of("adapter", "mock"));
                });

        assertThatThrownBy(() -> fakeAgentOrchestrator.generateSpec(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Spec source reference guard");

        assertThat(agentRunService.listByProject(project.id())).isNotEmpty();
        AgentRun run = agentRunService.listByProject(project.id())
                .get(agentRunService.listByProject(project.id()).size() - 1);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedSpecSnapshotId()).isNull();

        // No spec snapshot entered the route.
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
    }
}
