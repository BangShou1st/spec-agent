package com.specagent.agent.loop;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTriggerType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stale-anchor race: an external tip move between parent completion and
 * child creation must refuse the autonomous continuation.
 *
 * <p>The anchor rule under test is family-blind: the expected tip is the
 * parent's produced node when one exists, otherwise the parent's input
 * node. The live tip must still equal it or no child is created.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContinuationStaleAnchorRaceTest {

    @Autowired private ProjectService projectService;
    @Autowired private AgentRunRepository agentRunRepository;
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
        project = projectService.createProject("stale-anchor-" + UUID.randomUUID());
        configuredMaxCycles = loopProperties.getMaxCycles();
        loopProperties.setMaxCycles(10);
    }

    @AfterEach
    void cleanUp() {
        loopProperties.setMaxCycles(configuredMaxCycles);
        jdbcTemplate.update(
                "DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                        + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                (Object) project.id());
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
    void inputAnchoredParentRefusesChildAfterExternalTipMove() {
        Node root = answeredRoot("moved-input?");
        UUID parentId = saveInputRun(root.id());
        succeedCapability(parentId);

        moveTip(root.id());

        assertThat(coordinator.continueIfEligible(parentId)).isEmpty();
        assertThat(childCount(parentId)).isZero();
    }

    @Test
    void producedAnchoredParentRefusesChildAfterExternalTipMove() {
        Node note = graphCommandService.createRootDraftNode(
                project.id(), project.activeRouteId(), "NOTE", Map.of("text", "moved"));
        UUID parentId = saveProducedRun(note.id());

        moveTip(note.id());

        assertThat(coordinator.continueIfEligible(parentId)).isEmpty();
        assertThat(childCount(parentId)).isZero();
    }

    @Test
    void stableTipStillCreatesChild() {
        Node note = graphCommandService.createRootDraftNode(
                project.id(), project.activeRouteId(), "NOTE", Map.of("text", "stable"));
        UUID parentId = saveProducedRun(note.id());

        var child = coordinator.continueIfEligible(parentId);

        assertThat(child).isPresent();
        assertThat(child.get().inputNodeId()).isEqualTo(note.id());
    }

    @Test
    void routelessParentCreatesNothing() {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                null, AgentRunTriggerType.NODE_QUERY,
                null, null, null, null, null, null, AgentRunStatus.COMPLETED,
                "{}", "NODE_QUERY", null, null, Instant.now(), null,
                null, null, null));
        succeedCapability(runId);

        assertThat(coordinator.continueIfEligible(runId)).isEmpty();
        assertThat(childCount(runId)).isZero();
    }

    private Node answeredRoot(String question) {
        Node root = nodeService.createRootNode(
                project.id(), project.activeRouteId(), question, null, List.of(), true);
        answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "answer", "test-user");
        return root;
    }

    private UUID saveInputRun(UUID inputNodeId) {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                inputNodeId, null, null, null, null, null, AgentRunStatus.COMPLETED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                null, null, null));
        return runId;
    }

    private UUID saveProducedRun(UUID producedNodeId) {
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(runId, project.id(),
                project.activeRouteId(), AgentRunTriggerType.DECISION_CYCLE,
                null, null, producedNodeId, null, null, null, AgentRunStatus.COMPLETED,
                "{}", "DRAFT_QUESTION", null, null, Instant.now(), null,
                null, null, null));
        return runId;
    }

    private void succeedCapability(UUID runId) {
        UUID invocationId = UUID.randomUUID();
        String key = "stale-" + UUID.randomUUID();
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

    private void moveTip(UUID currentTip) {
        var moved = graphCommandService.appendContinuation(
                project.id(), project.activeRouteId(), currentTip,
                "NOTE", Map.of("text", "external move"));
        assertThat(moved.branched()).isFalse();
        UUID liveTip = routeRepository.findById(project.activeRouteId())
                .orElseThrow().tipNodeId();
        assertThat(liveTip).isNotEqualTo(currentTip);
    }

    private int childCount(UUID parentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id = ?",
                Integer.class, parentId);
        return count == null ? 0 : count;
    }
}
