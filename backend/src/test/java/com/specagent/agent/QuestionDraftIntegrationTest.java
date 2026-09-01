package com.specagent.agent;

import com.specagent.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Question-draft decision cycle through the deterministic fake engine: the
 * test driver drives exactly the production DRAFT_QUESTION path — one DECISION
 * call, policy auto-execute, INTERACTION node appended with runtime-owned ids.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuestionDraftIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;

    @Test
    void draftCreatesAgentRunAndNode() {
        Project project = projectService.createProject("Question draft project");

        AgentRun result = draftDriver.draftQuestion(project.id());

        assertThat(result).isNotNull();
        assertThat(result.projectId()).isEqualTo(project.id());
        assertThat(result.producedNodeId()).isNotNull();
        Node produced = nodeService.getNode(result.producedNodeId()).orElseThrow();
        assertThat(produced.projectId()).isEqualTo(project.id());
        assertThat(produced.question()).isNotBlank();
    }

    @Test
    void draftAttachesContextSnapshotToRun() {
        Project project = projectService.createProject("Question draft project");

        AgentRun result = draftDriver.draftQuestion(project.id());

        assertThat(result.contextSnapshotId()).isNotNull();
    }

    @Test
    void draftCompletesRun() {
        Project project = projectService.createProject("Question draft project");

        AgentRun result = draftDriver.draftQuestion(project.id());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.completedAt()).isNotNull();

        AgentRun loaded = agentRunService.getRun(result.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(loaded.trace()).contains("executing").contains("completed");
    }

    @Test
    void draftProducedNodeAdvancesRouteTip() {
        Project project = projectService.createProject("Question draft project");

        AgentRun result = draftDriver.draftQuestion(project.id());

        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(result.producedNodeId());
        assertThat(route.rootNodeId()).isEqualTo(result.producedNodeId());
    }

    @Test
    void draftFailsWithoutActiveRoute() {
        projectService.createProject("Question draft project");
        Project bareProject = new Project(Ids.random(), "Bare project", null,
                null, Instant.now(), Instant.now());
        projectRepository.save(bareProject);

        assertThatThrownBy(() -> draftDriver.draftQuestion(bareProject.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active route");
    }

    @Test
    void draftDoesNotCreateAnswerPatchOrSpecSnapshot() {
        Project project = projectService.createProject("Question draft project");

        AgentRun result = draftDriver.draftQuestion(project.id());

        assertThat(result.producedAnswerId()).isNull();
        assertThat(result.producedPatchId()).isNull();
        assertThat(result.producedSpecSnapshotId()).isNull();
        assertThat(agentRunService.listByProject(project.id())).hasSize(1);
    }

    @Test
    void draftChildNodeAppendsToExistingTip() {
        Project project = projectService.createProject("Question draft project");
        // First run creates the root node on the active route.
        AgentRun first = draftDriver.draftQuestion(project.id());
        Node firstNode = nodeService.getNode(first.producedNodeId()).orElseThrow();
        assertThat(firstNode.parentNodeId()).isNull();

        // An unanswered Question must remain the route tip; drafting a follow-up
        // requires the tip Question to be answered first.
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                firstNode.id(), null, "answered first question", "test-user");

        // Second run must create a child of the route tip, not a new root.
        AgentRun second = draftDriver.draftQuestion(project.id());
        Node secondNode = nodeService.getNode(second.producedNodeId()).orElseThrow();
        assertThat(secondNode.parentNodeId()).isEqualTo(firstNode.id());
        assertThat(secondNode.id()).isNotEqualTo(firstNode.id());
    }

    @Test
    void fakeDraftIsDeterministic() {
        Project project = projectService.createProject("Question draft project");

        Node draft1 = nodeService.getNode(
                draftDriver.draftQuestion(project.id()).producedNodeId()).orElseThrow();
        // The second draft chains off the first tip. An unanswered Question must
        // stay the tip, so answer the first node before drafting the next one.
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                draft1.id(), null, "answered for determinism check", "test-user");
        Node draft2 = nodeService.getNode(
                draftDriver.draftQuestion(project.id()).producedNodeId()).orElseThrow();

        assertThat(draft1.question()).isEqualTo(draft2.question());
        assertThat(draft1.question()).isEqualTo("What is the most important outcome?");
        assertThat(draft1.purpose()).isEqualTo("This clarifies the primary requirement goal.");
        assertThat(draft1.allowFreeAnswer()).isTrue();
        assertThat(draft1.options()).hasSize(1);
        assertThat(draft1.options().get(0).label()).isEqualTo("Clarify the primary goal");
    }
}
