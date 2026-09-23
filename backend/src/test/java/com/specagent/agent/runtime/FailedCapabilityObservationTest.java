package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1, section 17: a FAILED capability invocation must already be
 * visible to future snapshots on the same route. Only then may the
 * coordinator treat a durable failure as consumable new observation.
 *
 * <p>Read-only verification of the existing projection; production
 * projection behavior stays untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FailedCapabilityObservationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private CapabilityRuntime capabilityRuntime;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired
    private NodeService nodeService;

    @Test
    void failedInvocationIsProjectedIntoFutureSnapshots() {
        Project project = projectService.createProject("failed-observation");
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), "root?", null, List.of(), true);
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                root.id(), null, null, null, null, null, AgentRunStatus.COMPLETED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null));

        CapabilityResult result = capabilityRuntime.invoke(
                "failed-" + UUID.randomUUID(), "unknown.capability",
                project.id(), runId, Map.of());
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);

        ContextSnapshot snapshot = contextBuilder.buildForRoute(
                project.id(), project.activeRouteId(), root.id(),
                UUID.randomUUID(), ContextOperationType.NORMAL);
        boolean visible = snapshotBuilder.build(snapshot).capabilityResults().stream()
                .anyMatch(view -> "unknown.capability".equals(view.capabilityId())
                        && "FAILED".equals(view.status()));

        assertThat(visible).isTrue();
    }
}
