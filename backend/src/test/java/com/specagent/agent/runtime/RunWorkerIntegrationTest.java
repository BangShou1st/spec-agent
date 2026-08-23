package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question-draft decision cycle through the local fake engine: a queued run
 * is claimed, executes ONE DECISION against the decision engine port,
 * records every phase as an append-only event, and the auto-executed
 * REQUEST_USER_INPUT proposal lands as a real INTERACTION node — the route's
 * root node on an empty route, a tip child afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunWorkerIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private com.specagent.route.RouteService routeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.agent.runevent.AgentRunEventRepository eventRepository;

    @Test
    void emptyRouteDraftAppendsTheRootQuestionNode() {
        Project project = projectService.createProject("决策周期项目");
        assertThat(projectService.getProject(project.id()).orElseThrow()
                .activeRouteId()).isNotNull();

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(run);

        AgentRun completed = runService.getRun(run.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.producedNodeId()).isNotNull();

        Node root = nodeService.getNode(completed.producedNodeId()).orElseThrow();
        assertThat(root.question()).isEqualTo("What is the most important outcome?");
        assertThat(root.parentNodeId()).isNull();
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId())
                .isEqualTo(root.id());

        List<String> lifecycle = eventRepository.findByRunId(run.id()).stream()
                .map(event -> event.eventType())
                .collect(Collectors.toList());
        // Pure continuation: one DECISION, no STATE_UPDATE phase.
        assertThat(lifecycle).containsExactly(
                "RUN_CREATED",
                "SNAPSHOT_BUILT",
                "DECISION_STARTED",
                "PROPOSAL_CREATED",
                "EXECUTING",
                "RUN_COMPLETED");
    }

    @Test
    void draftAfterARootAppendsAChildAtTheTip() {
        Project project = projectService.createProject("认领执行项目");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "谁是最主要的用户？", null, List.of(), true);

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(run);

        AgentRun completed = runService.getRun(run.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        Node child = nodeService.getNode(completed.producedNodeId()).orElseThrow();
        assertThat(child.parentNodeId()).isEqualTo(root.id());

        assertThat(runService.claimNext()).isEmpty();
    }
}
