package com.specagent.agent;

import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.common.Json;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.testing.FakeModelAdapter;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Failure-path integration tests for the fake full loop: rejected proposals
 * must never be persisted, the run must end FAILED, and the route tip must not
 * be polluted.
 *
 * <p>Deliberately not {@code @Transactional}: the whole point is that a FAILED
 * agent run must remain queryable after the surrounding agent cycle fails, and
 * rejected artifacts must stay absent from the database. A test transaction
 * would hide rollback behavior.
 */
@SpringBootTest
@ActiveProfiles("test")
class FakeFullLoopFailureIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
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
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void fakeAnswerRunRejectedPatchDoesNotPersistPatch() {
        Project project = projectService.createProject("Patch rejection project");
        Node tip = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the primary outcome?", null, List.of(), true);

        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            Map<String, String> trace = Map.of("adapter", "mock", "deterministic", "true",
                    "task", request.taskType().code());
            return switch (request.taskType()) {
                case INTERPRET_ANSWER -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        json.write(new AnswerInterpretationResult(
                                List.of("clarified"), List.of(), List.of(), List.of())),
                        trace);
                case DRAFT_ANSWER_PATCH -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        // Model-facing patch shape without runtime-owned ids:
                        // the blank claim text violates the strict output
                        // contract, so the parser rejects the patch before it
                        // may be reflected or persisted.
                        json.write(Map.of("claims", List.of(
                                Map.of("kind", "goal", "text", " ",
                                        "status", "confirmed", "confidence", 0.9)))),
                        trace);
                default -> throw new IllegalStateException("Unexpected task " + request.taskType());
            };
        });

        assertThatThrownBy(() -> fakeAgentOrchestrator.answerActiveNodeAndDraftNext(project.id(), "clarified"))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("non-blank");

        // The failed run is queryable.
        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedAnswerId()).isNotNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedNodeId()).isNull();

        // No patch entered requirement state.
        assertThat(answerPatchService.findByRoute(project.activeRouteId())).isEmpty();

        // Route tip is not polluted.
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(tip.id());
    }

    @Test
    void fakeSpecRunRejectsUngroundedSpecDraft() {
        Project project = projectService.createProject("Spec rejection project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What matters most?", null, List.of(), true);

        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(
                    request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                    AgentAction.GENERATE_SPEC,
                    json.write(new SpecDraft(
                            Map.of("Overview", "content without source references"),
                            List.of(),
                            Map.of())),
                    Map.of("adapter", "mock"));
        });

        assertThatThrownBy(() -> fakeAgentOrchestrator.generateSpec(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Spec grounding rejected");

        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
        AgentRun run = agentRunService.listByProject(project.id()).get(0);
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

        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(
                    request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                    AgentAction.GENERATE_SPEC,
                    json.write(new SpecDraft(
                            Map.of("Overview", "grounded looking content"),
                            List.of(),
                            Map.of("Overview", List.of("answer:" + nonexistentAnswerId)))),
                    Map.of("adapter", "mock"));
        });

        assertThatThrownBy(() -> fakeAgentOrchestrator.generateSpec(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Spec source reference guard");

        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.completedAt()).isNotNull();
        assertThat(run.producedSpecSnapshotId()).isNull();

        // No spec snapshot entered the route.
        assertThat(specSnapshotService.listByRoute(project.activeRouteId())).isEmpty();
    }
}
