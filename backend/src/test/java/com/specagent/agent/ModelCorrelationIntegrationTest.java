package com.specagent.agent;

import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.testing.FakeModelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runtime correlation validation is owned by the runtime, not by the gateway:
 * a gateway returning a response for the wrong agentRunId, contextSnapshotId or
 * taskType must fail the run before anything derived from that response can be
 * persisted.
 *
 * <p>The default fake adapter is replaced by a mock whose proposals corrupt the
 * selected correlation field, so the orchestrator must reject it at correlation
 * time. The mock replacement keeps the adapter's primary status, exactly like
 * {@link FakeAgentOrchestratorFailureIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class ModelCorrelationIntegrationTest {

    enum CorruptField {AGENT_RUN_ID, CONTEXT_SNAPSHOT_ID, TASK_TYPE}

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;

    @MockBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void wrongAgentRunIdIsRejectedAndNothingPersisted() {
        runCorruptedAndAssertRejected(CorruptField.AGENT_RUN_ID, "agentRunId");
    }

    @Test
    void wrongContextSnapshotIdIsRejectedAndNothingPersisted() {
        runCorruptedAndAssertRejected(CorruptField.CONTEXT_SNAPSHOT_ID, "contextSnapshotId");
    }

    @Test
    void wrongTaskTypeIsRejectedAndNothingPersisted() {
        runCorruptedAndAssertRejected(CorruptField.TASK_TYPE, "taskType");
    }

    private void runCorruptedAndAssertRejected(CorruptField field, String messagePart) {
        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            UUID runId = field == CorruptField.AGENT_RUN_ID
                    ? UUID.randomUUID() : request.agentRunId();
            UUID snapshotId = field == CorruptField.CONTEXT_SNAPSHOT_ID
                    ? UUID.randomUUID() : request.contextSnapshotId();
            AgentTaskType taskType = field == CorruptField.TASK_TYPE
                    ? AgentTaskType.DRAFT_SPEC : request.taskType();
            return new ModelResponse(runId, snapshotId, taskType,
                    AgentAction.ASK_NEXT_QUESTION, "{}", Map.of("adapter", "corrupted"));
        });

        Project project = projectService.createProject("Correlation project");

        assertThatThrownBy(() -> orchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining(messagePart);

        AgentRun run = agentRunService.listByProject(project.id()).get(0);
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.trace()).contains("model_called").contains("failed");
        assertThat(run.producedNodeId()).isNull();
        assertThat(run.producedAnswerId()).isNull();
        assertThat(run.producedPatchId()).isNull();
        assertThat(run.producedSpecSnapshotId()).isNull();

        // No node was created by the rejected proposal.
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isNull();
        assertThat(route.rootNodeId()).isNull();
    }
}
