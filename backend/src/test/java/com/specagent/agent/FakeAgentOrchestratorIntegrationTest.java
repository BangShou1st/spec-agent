package com.specagent.agent;

import com.specagent.agent.contracts.NodeDraft;
import com.specagent.common.Ids;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeAgentOrchestratorIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private ContextBuilder contextBuilder;

    @Test
    void fakeOrchestratorCreatesAgentRunAndNode() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.run()).isNotNull();
        assertThat(result.run().projectId()).isEqualTo(project.id());
        assertThat(result.producedNode()).isNotNull();
        assertThat(result.producedNode().projectId()).isEqualTo(project.id());
        assertThat(result.producedNode().question()).isNotBlank();
    }

    @Test
    void fakeOrchestratorAttachesContextSnapshotToRun() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.run().contextSnapshotId()).isEqualTo(result.contextSnapshot().id());
        assertThat(result.modelResponse().requestContextSnapshotId()).isEqualTo(result.contextSnapshot().id());
        assertThat(result.modelResponse().requestAgentRunId()).isEqualTo(result.run().id());
    }

    @Test
    void fakeOrchestratorCompletesRun() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.run().completedAt()).isNotNull();
        assertThat(result.modelResponse().action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);

        AgentRun loaded = agentRunService.getRun(result.run().id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(loaded.trace()).contains("completed");
    }

    @Test
    void fakeOrchestratorProducedNodeAdvancesRouteTip() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.run().producedNodeId()).isEqualTo(result.producedNode().id());
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(result.producedNode().id());
        assertThat(route.rootNodeId()).isEqualTo(result.producedNode().id());
    }

    @Test
    void fakeOrchestratorFailsWithoutActiveRoute() {
        Project project = projectService.createProject("Fake agent project");
        Project bareProject = new Project(Ids.random(), "Bare project", null,
                project.defaultProfileId(), Instant.now(), Instant.now());
        projectRepository.save(bareProject);

        assertThatThrownBy(() -> fakeAgentOrchestrator.draftNextQuestion(bareProject.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active route");
    }

    @Test
    void fakeOrchestratorDoesNotCreateAnswerPatchOrSpecSnapshot() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.run().producedAnswerId()).isNull();
        assertThat(result.run().producedPatchId()).isNull();
        assertThat(result.run().producedSpecSnapshotId()).isNull();
        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
    }

    @Test
    void fakeOrchestratorChildNodeAppendsToExistingTip() {
        Project project = projectService.createProject("Fake agent project");
        // First run creates the root node on the active route.
        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        assertThat(first.producedNode().parentNodeId()).isNull();

        // Second run must create a child of the route tip, not a new root.
        FakeAgentRunResult second = fakeAgentOrchestrator.draftNextQuestion(project.id());
        assertThat(second.producedNode().parentNodeId()).isEqualTo(first.producedNode().id());
        assertThat(second.producedNode().id()).isNotEqualTo(first.producedNode().id());
    }

    @Test
    void fakeOrchestratorRunUsesFrozenContextHash() {
        Project project = projectService.createProject("Fake agent project");

        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        assertThat(result.contextSnapshot().contextHash()).isNotBlank();
        assertThat(result.contextSnapshot().operationType()).isEqualTo(ContextOperationType.NORMAL);
        assertThat(result.modelResponse().taskType()).isEqualTo(AgentTaskType.DRAFT_NODE);
    }

    @Test
    void fakeOrchestratorFakeDraftIsDeterministic() {
        Project project = projectService.createProject("Fake agent project");

        NodeDraft draft1 = draftOf(fakeAgentOrchestrator.draftNextQuestion(project.id()));
        NodeDraft draft2 = draftOf(fakeAgentOrchestrator.draftNextQuestion(project.id()));

        assertThat(draft1.question()).isEqualTo(draft2.question());
        assertThat(draft1.allowFreeAnswer()).isTrue();
        assertThat(draft1.options()).isEmpty();
    }

    private NodeDraft draftOf(FakeAgentRunResult result) {
        Node produced = result.producedNode();
        return new NodeDraft(produced.question(), produced.purpose(), produced.options(),
                produced.allowFreeAnswer());
    }
}