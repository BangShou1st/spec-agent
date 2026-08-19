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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Failure-path integration tests for the fake agent orchestrator.
 *
 * <p>Deliberately not {@code @Transactional}: the whole point of this hardening
 * is that a FAILED agent run must remain queryable after the surrounding agent
 * cycle fails. A test transaction would hide rollback behavior.
 */
@SpringBootTest
@ActiveProfiles("test")
class FakeAgentOrchestratorFailureIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;

    @MockBean
    private FakeModelAdapter fakeModelAdapter;

    @Test
    void fakeOrchestratorPersistsFailedRunWhenModelReturnsUnexpectedAction() {
        Project project = projectService.createProject("Failure project");
        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            return new ModelResponse(
                    request.agentRunId(),
                    request.contextSnapshotId(),
                    request.taskType(),
                    AgentAction.STOP,
                    "{}",
                    Map.of("adapter", "mock"));
        });

        assertThatThrownBy(() -> fakeAgentOrchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("Expected ASK_NEXT_QUESTION");

        assertFailedRun(project);
    }

    @Test
    void fakeOrchestratorPersistsFailedRunWhenModelAdapterThrows() {
        Project project = projectService.createProject("Failure project");
        when(fakeModelAdapter.run(any(ModelRequest.class)))
                .thenThrow(new ModelContractException("fake adapter exploded"));

        assertThatThrownBy(() -> fakeAgentOrchestrator.draftNextQuestion(project.id()))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("fake adapter exploded");

        assertFailedRun(project);
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
