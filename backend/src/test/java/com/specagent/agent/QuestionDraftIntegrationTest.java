package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.QuestionDraftIntegrationTest;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.AgentRunService;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
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
    @Autowired
    private AnswerPatchService answerPatchService;

    private void finalizeAndCheckpoint(Project project, Node node, String freeText) {
        var answer = answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                node.id(), null, freeText, "test-user");
        answerPatchService.save(project.id(), project.activeRouteId(), node.id(),
                answer.id(), java.util.List.of(), null);
    }

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
        finalizeAndCheckpoint(project, firstNode, "answered first question");

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
        // The deterministic fake advances past the answered question (repeating
        // it would trip the enforced RESOLVED_BLOCKER rule), so the follow-up
        // is the second rung of the clarification ladder.
        finalizeAndCheckpoint(project, draft1, "answered for determinism check");
        Node draft2 = nodeService.getNode(
                draftDriver.draftQuestion(project.id()).producedNodeId()).orElseThrow();

        assertThat(draft1.question()).isEqualTo("What is the most important outcome?");
        assertThat(draft1.purpose()).isEqualTo("This clarifies the primary requirement goal.");
        assertThat(draft1.allowFreeAnswer()).isTrue();
        assertThat(draft1.options()).hasSize(1);
        assertThat(draft1.options().get(0).label()).isEqualTo("Clarify the primary goal");
        assertThat(draft2.question()).isEqualTo("What is the next most important outcome?");
        assertThat(draft2.purpose()).isEqualTo("This clarifies the next requirement goal.");
        assertThat(draft2.allowFreeAnswer()).isTrue();
        assertThat(draft2.options()).hasSize(1);
        assertThat(draft2.options().get(0).label()).isEqualTo("Clarify the next goal");
    }
}
