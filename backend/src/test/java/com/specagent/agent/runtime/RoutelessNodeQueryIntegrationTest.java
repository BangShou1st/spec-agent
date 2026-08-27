package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.ContextSnapshotRepository;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routeless NODE_QUERY as a first-class context: a floating persisted node
 * (routeIds=[]) in a project with NO active route can be the anchor of an
 * "Ask AI" query. The ContextSnapshot persists with route_id = NULL, the
 * snapshot never emits a {@code route:null} source ref, the query reaches a
 * terminal COMPLETED result, and the query leaves the graph unchanged.
 *
 * <p>Exercises the real {@code ContextSnapshotRepository} persistence path —
 * the snapshot row must survive with a NULL route id.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoutelessNodeQueryIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private RouteService routeService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private ContextSnapshotRepository snapshotRepository;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private Node floatingNode;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("无路线节点问答 " + UUID.randomUUID());
        // Remove the active route, so the project has NO active route at all.
        UUID activeRouteId = project.activeRouteId();
        routeService.archiveRoute(project.id(), activeRouteId);
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        // A floating persisted canonical node, created with no route at all.
        floatingNode = commandService.createFloatingDraftNode(
                project.id(), null, "IDEA", Map.of("text", "无路线的漂浮想法"));
    }

    @Test
    void routelessNodeQueryPersistsNullRouteSnapshotAndCompletes() {
        long routesBefore = countRoutes(project.id());
        long nodesBefore = countNodes(project.id());
        long answersBefore = countAnswers(project.id());
        long relationsBefore = countRelations(project.id());

        UUID runId = runService.createQueuedNodeQuery(
                project.id(), null, floatingNode.id(), "这条想法有什么风险？");

        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);

        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // The ContextSnapshot persisted with route_id = NULL and includes the
        // anchor node.
        ContextSnapshot snapshot = snapshotRepository.findById(run.contextSnapshotId()).orElseThrow();
        assertThat(snapshot.routeId()).isNull();
        assertThat(snapshot.includedNodeIds()).contains(floatingNode.id());
        // The anchor lineage is exactly the floating node itself.
        assertThat(snapshot.includedNodeIds()).hasSize(1);
        // The persisted row really carries NULL route_id.
        UUID persistedRouteId = jdbcTemplate.queryForObject(
                "SELECT route_id FROM context_snapshots WHERE id = ?", UUID.class, snapshot.id());
        assertThat(persistedRouteId).isNull();

        // The model-facing projection never emits a route:null source ref and
        // carries an explicit route-less route context.
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);
        assertThat(projected.routeId()).isNull();
        assertThat(projected.routeContext().routeId()).isNull();
        assertThat(projected.allowedSourceRefs())
                .noneMatch(ref -> ref.startsWith("route:"));

        // Exactly one DECISION call; terminal RESPOND message present.
        var phases = eventService.findByRunId(runId);
        assertThat(phases.stream()
                .filter(e -> "DECISION_STARTED".equals(e.eventType())).count()).isEqualTo(1);
        assertThat(phases.stream()
                .anyMatch(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType())))
                .isTrue();

        // The query never mutated the graph: routes, nodes, answers and
        // relations are exactly as before.
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        assertThat(countNodes(project.id())).isEqualTo(nodesBefore);
        assertThat(countAnswers(project.id())).isEqualTo(answersBefore);
        assertThat(countRelations(project.id())).isEqualTo(relationsBefore);
        // Only the floating-node creation operation exists in the log.
        assertThat(commandService.listOperations(project.id())).hasSize(1);
    }

    private long countRoutes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routes WHERE project_id = ?", Long.class, projectId);
    }

    private long countNodes(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ?", Long.class, projectId);
    }

    private long countAnswers(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answers WHERE project_id = ?", Long.class, projectId);
    }

    private long countRelations(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM node_relations WHERE project_id = ?", Long.class, projectId);
    }
}