package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
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
 * Stage A deterministic decision cycle through the local fake engine: a queued
 * Run is claimed, executes STATE_UPDATE and DECISION against the decision
 * engine port, records every phase as an append-only event, and completes
 * without touching the Graph (proposals are recorded, never executed).
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
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.agent.runevent.AgentRunEventRepository eventRepository;

    @Test
    void executesFullDecisionCycleAndRecordsAllPhases() {
        Project project = projectService.createProject("决策周期项目");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "最重要的目标是什么？", null, List.of(), true);

        AgentRun run = runService.createQueuedRun(project.id());
        worker.executeRun(run);

        AgentRun completed = runService.getRun(run.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.producedNodeId()).isNull();

        List<AgentRunPhase> phases = phasesOf(run.id());
        assertThat(phases).containsExactly(
                AgentRunPhase.CREATED,
                AgentRunPhase.SNAPSHOT_BUILT,
                AgentRunPhase.STATE_UPDATING,
                AgentRunPhase.STATE_UPDATED,
                AgentRunPhase.DECIDING,
                AgentRunPhase.PROPOSAL_CREATED,
                AgentRunPhase.COMPLETED);

        // The proposal is recorded as an event only — no Graph mutation.
        String proposalEvent = eventText(run.id(), "PROPOSAL_CREATED");
        assertThat(proposalEvent).contains(ActionFamily.REQUEST_USER_INPUT.name());
    }

    @Test
    void claimNextExecutesQueuedRunsAtomically() {
        Project project = projectService.createProject("认领执行项目");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "谁是最主要的用户？", null, List.of(), true);

        runService.createQueuedRun(project.id());
        worker.tryClaimAndExecute();

        // The claimed run reached a terminal state; nothing stays queued.
        assertThat(runService.claimNext()).isEmpty();
    }

    private List<AgentRunPhase> phasesOf(java.util.UUID runId) {
        return runEvents(runId).stream().map(AgentRunEvent::phase).collect(Collectors.toList());
    }

    private String eventText(java.util.UUID runId, String eventType) {
        return runEvents(runId).stream()
                .filter(event -> event.eventType().equals(eventType))
                .map(event -> event.eventType() + " " + event.payload())
                .collect(Collectors.joining("\n"));
    }

    private List<AgentRunEvent> runEvents(java.util.UUID runId) {
        return eventRepository.findByRunId(runId);
    }
}
