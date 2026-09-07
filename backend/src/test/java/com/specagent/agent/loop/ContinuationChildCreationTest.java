package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.answer.AnswerService;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityResult;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2: idempotent continuation child creation.
 *
 * <p>No test depends on the default max-cycles value: tests that need a
 * non-default budget set it explicitly and restore it afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContinuationChildCreationTest {

    @Autowired private ProjectService projectService;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private AgentRunEventService eventService;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ContinuationCoordinator coordinator;
    @Autowired private LoopProperties loopProperties;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private int configuredMaxCycles;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("child-creation-" + UUID.randomUUID());
        configuredMaxCycles = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(10);
    }

    @AfterEach
    void cleanUp() {
        loopProperties.setMaxCycles(configuredMaxCycles);
        jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE run_id IN "
                        + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                (Object) project.id());
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?",
                (Object) project.id());
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?",
                (Object) project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?",
                (Object) project.id());
    }

    @Test
    void eligibleParentCreatesOneLinkedChild() {
        Node note = graphCommandService.createRootDraftNode(
                project.id(), project.activeRouteId(), "NOTE", Map.of("text", "anchor"));
        UUID parentId = saveRun(AgentRunStatus.COMPLETED, note.id(), null, null, null);
        UUID tip = routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId();

        Optional<AgentRun> child = coordinator.continueIfEligible(parentId);

        assertThat(child).isPresent();
        AgentRun created = child.get();
        assertThat(created.triggerType()).isEqualTo(AgentRunTriggerType.CONTINUE_CYCLE);
        assertThat(created.status()).isEqualTo(AgentRunStatus.CREATED);
        assertThat(created.operation()).isEqualTo("CONTINUE");
        assertThat(created.parentRunId()).isEqualTo(parentId);
        assertThat(created.rootRunId()).isEqualTo(parentId);
        assertThat(created.cycleIndex()).isEqualTo(1);
        assertThat(created.inputNodeId()).isEqualTo(tip);
        assertThat(created.idempotencyKey()).isEqualTo("continue:" + parentId);
        var createdEvent = eventService.findByRunId(created.id()).stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(createdEvent.payload().get("parentRunId")).isEqualTo(parentId.toString());
        assertThat(createdEvent.payload().get("rootRunId")).isEqualTo(parentId.toString());
        assertThat(createdEvent.payload().get("cycleIndex")).isEqualTo(1);
    }

    @Test
    void concurrentCreationConvergesOnOneChild() throws Exception {
        UUID parentId = capabilityParent();
        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier startLine = new CyclicBarrier(racers);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit((Callable<UUID>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return coordinator.continueIfEligible(parentId)
                            .orElseThrow().id();
                }));
            }
            List<UUID> returnedIds = new ArrayList<>();
            for (Future<UUID> future : futures) {
                returnedIds.add(future.get(30, TimeUnit.SECONDS));
            }
            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                    Integer.class, parentId);
            assertThat(rowCount).as("exactly one persisted child").isEqualTo(1);
            assertThat(returnedIds.stream().distinct().count())
                    .as("every caller observes the same child")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentParentsProduceDifferentChildren() {
        UUID first = capabilityParent();
        UUID second = capabilityParent();

        UUID firstChild = coordinator.continueIfEligible(first).orElseThrow().id();
        UUID secondChild = coordinator.continueIfEligible(second).orElseThrow().id();

        assertThat(firstChild).isNotEqualTo(secondChild);
        assertThat(agentRunRepository.findChildByParentRunId(first).orElseThrow().id())
                .isEqualTo(firstChild);
        assertThat(agentRunRepository.findChildByParentRunId(second).orElseThrow().id())
                .isEqualTo(secondChild);
    }

    @Test
    void repeatCallReturnsSameChild() {
        UUID parentId = capabilityParent();

        UUID first = coordinator.continueIfEligible(parentId).orElseThrow().id();
        UUID second = coordinator.continueIfEligible(parentId).orElseThrow().id();

        assertThat(second).isEqualTo(first);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, parentId);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void existingChainKeepsRootAndIncrementsCycle() {
        int previous = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(8);
        try {
            UUID rootId = saveRun(AgentRunStatus.COMPLETED, null, null, null, 0);
            UUID parentId = saveRun(AgentRunStatus.COMPLETED, null, rootId, rootId, 2);
            succeedCapability(parentId);

            AgentRun child = coordinator.continueIfEligible(parentId).orElseThrow();

            assertThat(child.rootRunId()).isEqualTo(rootId);
            assertThat(child.cycleIndex()).isEqualTo(3);
        } finally {
            loopProperties.setMaxCycles(previous);
        }
    }

    @Test
    void maxCyclesOneStopsAtRoot() {
        int previous = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(1);
        try {
            UUID parentId = capabilityParent();

            assertThat(coordinator.continueIfEligible(parentId)).isEmpty();
            assertThat(agentRunRepository.findChildByParentRunId(parentId)).isEmpty();
        } finally {
            loopProperties.setMaxCycles(previous);
        }
    }

    @Test
    void maxCyclesTwoAllowsExactlyOneChild() {
        int previous = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(2);
        try {
            UUID parentId = capabilityParent();
            UUID childId = coordinator.continueIfEligible(parentId).orElseThrow().id();
            succeedCapability(childId);
            agentRunRepository.updateStatus(childId, AgentRunStatus.COMPLETED,
                    Instant.now(), "{}");

            assertThat(coordinator.continueIfEligible(childId)).isEmpty();
            assertThat(agentRunRepository.findChildByParentRunId(childId)).isEmpty();
            assertThat(agentRunRepository.findChildByParentRunId(parentId)
                    .orElseThrow().id()).isEqualTo(childId);
        } finally {
            loopProperties.setMaxCycles(previous);
        }
    }

    @Test
    void maxCyclesFiveFollowsTheFormula() {
        int previous = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(5);
        try {
            UUID open = saveRun(AgentRunStatus.COMPLETED, null, null, null, 3);
            succeedCapability(open);
            assertThat(coordinator.continueIfEligible(open)).isPresent();

            UUID closed = saveRun(AgentRunStatus.COMPLETED, null, null, null, 4);
            succeedCapability(closed);
            assertThat(coordinator.continueIfEligible(closed)).isEmpty();
        } finally {
            loopProperties.setMaxCycles(previous);
        }
    }

    @Test
    void unrelatedContentDoesNotChangeChildIdentity() {
        UUID parentId = capabilityParent();
        UUID first = coordinator.continueIfEligible(parentId).orElseThrow().id();

        eventService.append(parentId, AgentRunPhase.COMPLETED, "UNRELATED_NOISE",
                Map.of("conflict", "text changed", "unknowns", "more text"));
        Node extra = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "conflict: A excludes B, unknowns remain", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                extra.id(), null, "extra answer", "test-user");

        UUID second = coordinator.continueIfEligible(parentId).orElseThrow().id();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentFactSourcesShareOneCreationPath() {
        Node note = graphCommandService.createRootDraftNode(
                project.id(), project.activeRouteId(), "NOTE", Map.of("text", "source"));
        UUID nodeParent = saveRun(AgentRunStatus.COMPLETED, note.id(), null, null, null);
        UUID capabilityParent = capabilityParent();

        Optional<AgentRun> fromNode = coordinator.continueIfEligible(nodeParent);
        Optional<AgentRun> fromCapability = coordinator.continueIfEligible(capabilityParent);

        assertThat(fromNode).isPresent();
        assertThat(fromCapability).isPresent();
        assertThat(fromNode.get().triggerType())
                .isEqualTo(fromCapability.get().triggerType());
        assertThat(fromNode.get().operation()).isEqualTo(fromCapability.get().operation());
    }

    @Test
    void ineligibleParentCreatesNothing() {
        UUID bare = saveRun(AgentRunStatus.COMPLETED, null, null, null, null);
        assertThat(coordinator.continueIfEligible(bare)).isEmpty();

        UUID failed = saveRun(AgentRunStatus.FAILED, null, null, null, null);
        assertThat(coordinator.continueIfEligible(failed)).isEmpty();
    }

    @Test
    void childStaysCreatedWithoutExecution() {
        UUID parentId = capabilityParent();

        AgentRun child = coordinator.continueIfEligible(parentId).orElseThrow();

        assertThat(child.status()).isEqualTo(AgentRunStatus.CREATED);
        assertThat(agentRunRepository.findById(child.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);
    }

    private UUID saveRun(AgentRunStatus status, UUID producedNodeId,
                         UUID parentRunId, UUID rootRunId, Integer cycleIndex) {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, producedNodeId, null, null, null, status,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                parentRunId, rootRunId, cycleIndex));
        return runId;
    }

    private UUID capabilityParent() {
        UUID tip = routeRepository.findById(project.activeRouteId()).orElseThrow().tipNodeId();
        UUID parentId = saveInputRun(AgentRunStatus.COMPLETED, tip);
        succeedCapability(parentId);
        return parentId;
    }

    private UUID saveInputRun(AgentRunStatus status, UUID inputNodeId) {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                inputNodeId, null, null, null, null, null, status,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                null, null, null));
        return runId;
    }

    private void succeedCapability(UUID runId) {
        UUID invocationId = UUID.randomUUID();
        String key = "child-" + UUID.randomUUID();
        if (!invocationRepository.claim(new CapabilityInvocation(
                invocationId, key, "resource.extract_text",
                project.id(), runId, Map.of()))) {
            throw new IllegalStateException("capability claim lost for test setup: " + key);
        }
        invocationRepository.complete(invocationId, new CapabilityResult(
                invocationId, key, "resource.extract_text",
                CapabilityResult.Status.SUCCEEDED, Map.of("excerpt", "evidence"),
                List.of(), Map.of(), List.of()));
    }
}
