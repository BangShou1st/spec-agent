package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.RelationView;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.common.Ids;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextRelation;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contextual AI query on an arbitrary node: exactly one DECISION call, the
 * answer returns as RESPOND_TO_USER, and the graph is never mutated.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NodeQueryIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository nodeRelationRepository;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;

    private Project project;
    private Node knowledgeNode;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("节点问答测试");
        var route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        knowledgeNode = commandService.createRootDraftNode(
                project.id(), route.id(), "REQUIREMENT", Map.of("text", "系统必须支持离线模式"));
    }

    @Test
    void nodeQueryAnswersWithContextAndNeverMutatesTheGraph() {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeRepository.findById(project.activeRouteId()).orElseThrow().id(),
                knowledgeNode.id(), "这个需求会影响哪些部分？");

        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);

        AgentRun run = agentRunService.getRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);

        // Exactly one model call (DECISION only; no STATE_UPDATE on queries).
        var phases = eventService.findByRunId(runId);
        long decisionStarts = phases.stream()
                .filter(e -> "DECISION_STARTED".equals(e.eventType())).count();
        long stateUpdates = phases.stream()
                .filter(e -> "STATE_UPDATE_STARTED".equals(e.eventType())).count();
        assertThat(decisionStarts).isEqualTo(1);
        assertThat(stateUpdates).isZero();

        // The deterministic fake engine answered with RESPOND_TO_USER.
        assertThat(phases.stream()
                .anyMatch(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType())))
                .isTrue();
        String message = (String) phases.stream()
                .filter(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType()))
                .findFirst().orElseThrow().payload().get("message");
        assertThat(message).contains("这个需求会影响哪些部分");

        // No graph mutation happened: the node's content is untouched and no
        // new nodes/routes were created.
        assertThat(commandService.listOperations(project.id())).hasSize(1); // the draft creation only
    }

    /**
     * Blocker 7 — a node query captures the anchor's 1-hop ACTIVE semantic
     * relations (direction preserved) but never recurses into the neighbours of
     * the related nodes, and never adds the related nodes to the lineage.
     */
    @Test
    void nodeQueryProjectsBoundedOneHopSemanticContext() {
        UUID routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        Node a = knowledgeNode; // already on the active route's lineage
        Node b = commandService.createFloatingDraftNode(project.id(), null, "NOTE",
                Map.of("text", "依赖项 B"));
        Node c = commandService.createFloatingDraftNode(project.id(), null, "NOTE",
                Map.of("text", "B 的下游 C"));

        // A DEPENDS_ON B (anchor is source); B DEPENDS_ON C must NOT be pulled in.
        nodeRelationRepository.save(new NodeRelation(Ids.random(), project.id(), a.id(), b.id(),
                NodeRelationType.DEPENDS_ON, NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE,
                null, null, Instant.now(), null));
        nodeRelationRepository.save(new NodeRelation(Ids.random(), project.id(), b.id(), c.id(),
                NodeRelationType.DEPENDS_ON, NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE,
                null, null, Instant.now(), null));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, a.id(), "这个需求依赖哪些部分？");

        assertThat(snapshot.relations()).contains(
                new ContextRelation(a.id(), b.id(), "DEPENDS_ON"));
        assertThat(snapshot.relatedNodeIds()).contains(b.id()).doesNotContain(c.id());
        // The lineage stays pure: the related node is never in it.
        assertThat(snapshot.includedNodeIds()).doesNotContain(b.id(), c.id());

        AgentInputSnapshot input = snapshotBuilder.build(snapshot);
        assertThat(input.relations()).contains(new RelationView(a.id(), b.id(), "DEPENDS_ON"));
        assertThat(input.relatedNodes()).anyMatch(ref ->
                ref.nodeId().equals(b.id()) && "OUTGOING".equals(ref.direction()));
    }
}
