package com.specagent.agent;

import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentRunLifecycleIntegrationTest {

    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private ContextBuilder contextBuilder;

    @Test
    void agentRunCanAttachContextSnapshot() {
        Project project = projectService.createProject("Run lifecycle project");
        UUID routeId = project.activeRouteId();

        AgentRun run = agentRunService.create(project.id(), routeId,
                AgentRunTriggerType.INITIAL_REQUIREMENT, null, null);
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(project.id(), run.id(),
                ContextOperationType.NORMAL);
        agentRunService.attachContext(run.id(), ctx.id(), "context_built");

        AgentRun loaded = agentRunService.getRun(run.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(AgentRunStatus.CONTEXT_BUILT);
        assertThat(loaded.contextSnapshotId()).isEqualTo(ctx.id());
        assertThat(loaded.trace()).contains("context_built");
    }

    @Test
    void agentRunCanRecordProducedNode() {
        Project project = projectService.createProject("Run lifecycle project");
        UUID routeId = project.activeRouteId();

        AgentRun run = agentRunService.create(project.id(), routeId,
                AgentRunTriggerType.INITIAL_REQUIREMENT, null, null);
        ContextSnapshot ctx = contextBuilder.buildFromActiveRoute(project.id(), run.id(),
                ContextOperationType.NORMAL);
        agentRunService.attachContext(run.id(), ctx.id(), "context_built");
        agentRunService.markModelCalled(run.id(), "{\"adapter\":\"fake\"}");
        agentRunService.markReflected(run.id(), "{\"accepted\":true}");

        Node node = nodeService.createRootNode(project.id(), routeId, "What is the goal?", null,
                List.of(), true);
        agentRunService.markPersistedNode(run.id(), node.id(), "produced_node");
        agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "completed");

        AgentRun loaded = agentRunService.getRun(run.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(loaded.producedNodeId()).isEqualTo(node.id());
        assertThat(loaded.contextSnapshotId()).isEqualTo(ctx.id());
        assertThat(loaded.completedAt()).isNotNull();
    }

    @Test
    void failedAgentRunGetsFailedStatus() {
        Project project = projectService.createProject("Run lifecycle project");
        UUID routeId = project.activeRouteId();

        AgentRun run = agentRunService.create(project.id(), routeId,
                AgentRunTriggerType.INITIAL_REQUIREMENT, null, null);
        agentRunService.fail(run.id(), "{\"error\":\"IllegalStateException\"}");

        AgentRun loaded = agentRunService.getRun(run.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(loaded.completedAt()).isNotNull();
    }
}