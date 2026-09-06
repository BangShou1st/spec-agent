package com.specagent.agent.snapshot;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.SideEffectClass;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability observations must follow route-lineage visibility: a result
 * produced in a shared prefix is visible to every inheriting branch, a
 * branch-private result stays on its own route, and unattributable rows
 * stay hidden (fail-closed). Observations never create graph truth.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CapabilityObservationLineageVisibilityIntegrationTest {

    @TestConfiguration
    static class LineageProbeCapabilityConfig {

        @Bean
        CapabilityAdapter lineageProbeCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.lineage-probe", "1",
                            "lineage visibility probe", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    List<String> refs = new ArrayList<>();
                    collectNodeRefs(invocation.arguments(), refs);
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("marker", invocation.invocationKey());
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            content, refs, Map.of("kind", "TEST_PROBE"), List.of());
                }

                private void collectNodeRefs(Object value, List<String> refs) {
                    if (value instanceof String ref && ref.startsWith("node:")) {
                        try {
                            UUID.fromString(ref.substring(5));
                            refs.add(ref);
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else if (value instanceof Map<?, ?> map) {
                        for (Object entry : map.values()) {
                            collectNodeRefs(entry, refs);
                        }
                    } else if (value instanceof List<?> list) {
                        for (Object entry : list) {
                            collectNodeRefs(entry, refs);
                        }
                    }
                }
            };
        }
    }

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private CapabilityRuntime capabilityRuntime;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private RouteRepository routeRepository;

    private UUID newRun(UUID projectId, UUID routeId, UUID inputNodeId) {
        AgentRun run = new AgentRun(UUID.randomUUID(), projectId, routeId,
                AgentRunTriggerType.ANSWER_CYCLE, inputNodeId, null,
                null, null, null, null, AgentRunStatus.CREATED,
                "{}", "TEST", "vis-" + UUID.randomUUID(), null, Instant.now(), null);
        agentRunRepository.save(run);
        return run.id();
    }

    private String invokeProbe(UUID projectId, UUID runId, UUID nodeId) {
        String key = "vis-" + UUID.randomUUID();
        Map<String, Object> args = nodeId == null ? Map.of()
                : Map.of("nodeRef", "node:" + nodeId);
        CapabilityResult result = capabilityRuntime.invoke(
                key, "test.lineage-probe", projectId, runId, args);
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        return key;
    }

    private List<String> observationMarkers(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId).orElseThrow();
        ContextSnapshot snapshot = contextBuilder.buildForRoute(
                projectId, routeId, route.tipNodeId(), UUID.randomUUID(),
                ContextOperationType.NORMAL);
        return snapshotBuilder.build(snapshot).capabilityResults().stream()
                .map(view -> String.valueOf(view.content().get("marker")))
                .toList();
    }
    @Test
    void branchesInheritSharedPrefixObservation() {
        Project project = projectService.createProject("分支继承共享观察");
        UUID route0 = project.activeRouteId();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));
        Node b = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "B")).node();
        graphCommandService.appendContinuation(
                project.id(), route0, b.id(), "NOTE", Map.of("text", "B2"));

        UUID run = newRun(project.id(), route0, b.id());
        String marker = invokeProbe(project.id(), run, b.id());

        UUID branchC = graphCommandService.appendContinuation(
                project.id(), route0, b.id(), "NOTE", Map.of("text", "C")).route().id();
        UUID branchD = graphCommandService.appendContinuation(
                project.id(), route0, b.id(), "NOTE", Map.of("text", "D")).route().id();

        assertThat(observationMarkers(project.id(), branchC)).contains(marker);
        assertThat(observationMarkers(project.id(), branchD)).contains(marker);
        assertThat(observationMarkers(project.id(), route0)).contains(marker);
    }

    @Test
    void branchPrivateObservationHiddenFromSibling() {
        Project project = projectService.createProject("分支私有观察隔离");
        UUID route0 = project.activeRouteId();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));
        Node b = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "B")).node();
        Node c = graphCommandService.appendContinuation(
                project.id(), route0, b.id(), "NOTE", Map.of("text", "C")).node();

        UUID run = newRun(project.id(), route0, c.id());
        String marker = invokeProbe(project.id(), run, c.id());

        UUID branchD = graphCommandService.appendContinuation(
                project.id(), route0, b.id(), "NOTE", Map.of("text", "D")).route().id();

        assertThat(observationMarkers(project.id(), route0)).contains(marker);
        assertThat(observationMarkers(project.id(), branchD)).doesNotContain(marker);
    }

    @Test
    void independentRouteSeesNothing() {
        Project project = projectService.createProject("独立路线隔离");
        UUID route0 = project.activeRouteId();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));
        Node p1 = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "P1")).node();

        UUID run = newRun(project.id(), route0, p1.id());
        String marker = invokeProbe(project.id(), run, p1.id());

        UUID route1 = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "Q1")).route().id();

        assertThat(observationMarkers(project.id(), route0)).contains(marker);
        assertThat(observationMarkers(project.id(), route1)).doesNotContain(marker);
    }
    @Test
    void sharedAncestorObservationVisibleToAllInheritingRoutes() {
        Project project = projectService.createProject("共享祖先观察可见");
        UUID route0 = project.activeRouteId();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));
        Node s = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "S")).node();
        graphCommandService.appendContinuation(
                project.id(), route0, s.id(), "NOTE", Map.of("text", "A1"));

        UUID route1 = graphCommandService.appendContinuation(
                project.id(), route0, s.id(), "NOTE", Map.of("text", "B1")).route().id();

        UUID run = newRun(project.id(), route0, s.id());
        String marker = invokeProbe(project.id(), run, s.id());

        assertThat(observationMarkers(project.id(), route0)).contains(marker);
        assertThat(observationMarkers(project.id(), route1)).contains(marker);
    }

    @Test
    void nullRunIdWithoutRefsIsHiddenFailClosed() {
        Project project = projectService.createProject("无归属观察隐藏");
        UUID route0 = project.activeRouteId();
        graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));

        String marker = invokeProbe(project.id(), null, null);

        assertThat(observationMarkers(project.id(), route0)).doesNotContain(marker);
    }

    @Test
    void nullRunIdWithLineageRefsIsVisible() {
        Project project = projectService.createProject("引用可定位观察可见");
        UUID route0 = project.activeRouteId();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route0, "NOTE", Map.of("text", "root"));
        Node b = graphCommandService.appendContinuation(
                project.id(), route0, root.id(), "NOTE", Map.of("text", "B")).node();

        String marker = invokeProbe(project.id(), null, b.id());

        assertThat(observationMarkers(project.id(), route0)).contains(marker);
    }
}
