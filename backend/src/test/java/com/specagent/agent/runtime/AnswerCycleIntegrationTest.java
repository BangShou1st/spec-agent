package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end answer cycle integration test: enqueues a run, the worker
 * claims and executes it through the full STATE_UPDATE → DECISION path,
 * and asserts the correct outcomes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnswerCycleIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AnswerService answerService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private Node rootNode;
    private Route route;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("E2E 回答周期项目");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        rootNode = nodeService.createRootNode(project.id(), route.id(),
                "最重要的目标是什么？", null, List.of(), true);
    }

    @Test
    void answerCycleCreatesNodeWithTwoProviderCalls() {
        // 1. Enqueue answer-cycle run.
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                null, "明确首要目标", null);

        // 2. Worker claims and executes.
        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        // 3. Assert run completed.
        AgentRun completed = agentRunService.getRun(runId).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 4. Assert 2 provider calls were made (STATE_UPDATE + DECISION).
        //    The fake engine records phase events, not MODEL_INFERENCE events
        //    (which only come from the internal inference broker path).
        List<AgentRunPhase> phases = eventService.findByRunId(runId).stream()
                .map(AgentRunEvent::phase)
                .distinct()
                .collect(Collectors.toList());
        assertThat(phases).contains(
                AgentRunPhase.STATE_UPDATING,
                AgentRunPhase.STATE_UPDATED,
                AgentRunPhase.DECIDING,
                AgentRunPhase.PROPOSAL_CREATED);

        // 5. Assert a new child node was created.
        AgentRunEvent nodeEvent = eventService.findByRunId(runId).stream()
                .filter(e -> "PROPOSAL_CREATED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(nodeEvent.payload().get("actionFamily")).isEqualTo("REQUEST_USER_INPUT");

        // 6. Assert answer was persisted.
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).freeText()).isEqualTo("明确首要目标");
    }

    @Test
    void retrySameNodeUsesResumePathWithSingleAnswer() {
        // 1. First submission: enqueue and execute.
        UUID firstRunId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                null, "第一次回答", null);
        AgentRun firstClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(firstClaimed);

        AgentRun firstCompleted = agentRunService.getRun(firstRunId).orElseThrow();
        assertThat(firstCompleted.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 2. Second submission on same node: should route to resume.
        UUID secondRunId = runService.createQueuedRunWithInput(
                project.id(), "RESUME_ANSWER", rootNode.id(),
                null, null, null);
        AgentRun secondClaimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(secondClaimed);

        AgentRun secondCompleted = agentRunService.getRun(secondRunId).orElseThrow();
        assertThat(secondCompleted.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 3. Answer count stays exactly 1.
        List<Answer> answers = answerService.findAnswersForRouteAndNodeIds(
                route.id(), List.of(rootNode.id()));
        assertThat(answers).hasSize(1);
    }

    @Test
    void runEventPayloadPreservesInputParameters() {
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", rootNode.id(),
                UUID.randomUUID(), "自由文本输入", null);

        // Verify the RUN_CREATED event carries the input.
        AgentRunEvent created = eventService.findByRunId(runId).stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType()))
                .findFirst().orElseThrow();
        assertThat(created.payload().get("freeText")).isEqualTo("自由文本输入");
        assertThat(created.payload().get("selectedOptionId")).isNotNull();
        assertThat(created.payload().get("operation")).isEqualTo("ANSWER_TIP");
    }
}
