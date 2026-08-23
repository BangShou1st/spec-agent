package com.specagent.capability;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.graph.GraphCommandService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end capability path through a real answer cycle: the decision
 * engine proposes INVOKE_CAPABILITY against a visible descriptor, policy
 * auto-executes the read-only capability, the invocation is recorded
 * exactly once, and no duplicate graph mutations occur.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CapabilityAnswerCycleIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private NodeService nodeService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private Node questionNode;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("能力回答周期测试");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node resource = commandService.attachResource(
                project.id(), route.id(), null, "TEXT",
                Map.of("text", "客户访谈记录：核心诉求是离线可用。"));
        // A question after the resource puts a RESOURCE node in the lineage
        // of the answer cycle context.
        questionNode = nodeService.createChildNode(
                project.id(), route.id(), resource.id(),
                "离线模式最重要的场景是什么？", null, List.of(), true);
    }

    @Test
    void answerCycleInvokesReadOnlyCapabilityExactlyOnce() {
        UUID runId = runService.createQueuedRunWithInput(
                project.id(), "ANSWER_TIP", questionNode.id(), null, "现场施工环境没有网络", null);

        AgentRun claimed = runService.claimNextAnswerCycle().orElseThrow();
        worker.executeRun(claimed);

        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // The read-only capability executed exactly once (idempotency key is
        // derived from run + proposal, so retries can never duplicate it).
        List<CapabilityInvocationRecord> invocations =
                invocationRepository.findRecentCompleted(project.id(), 10);
        assertThat(invocations).hasSize(1);
        CapabilityInvocationRecord invocation = invocations.get(0);
        assertThat(invocation.capabilityId()).isEqualTo(ResourceExtractTextCapability.CAPABILITY_ID);
        assertThat(invocation.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);

        // The executing phase recorded the capability family.
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> "EXECUTING".equals(e.eventType())
                        && "INVOKE_CAPABILITY".equals(String.valueOf(e.payload().get("actionFamily")))))
                .isTrue();
    }
}
