package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.RelationView;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.common.Ids;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextRelation;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeAuthorKind;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
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
 * 文件名:NodeQueryIntegrationTest.java
 *
 * 测试目标:针对任意节点的上下文感知 AI 问答:恰好一次 DECISION 调用,答案以
 * RESPOND_TO_USER 返回,图绝不被变更。另覆盖问答快照的有界一跳语义关系投影,
 * 以及直接关联 RESOURCE 节点无需工作区扫描即可暴露能力。
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
    @Autowired private NodeService nodeService;

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

        // 恰好一次模型调用(仅 DECISION;问答不执行 STATE_UPDATE)。
        var phases = eventService.findByRunId(runId);
        long decisionStarts = phases.stream()
                .filter(e -> "DECISION_STARTED".equals(e.eventType())).count();
        long stateUpdates = phases.stream()
                .filter(e -> "STATE_UPDATE_STARTED".equals(e.eventType())).count();
        assertThat(decisionStarts).isEqualTo(1);
        assertThat(stateUpdates).isZero();

        // 确定性 fake 引擎以 RESPOND_TO_USER 作答。
        assertThat(phases.stream()
                .anyMatch(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType())))
                .isTrue();
        String message = (String) phases.stream()
                .filter(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType()))
                .findFirst().orElseThrow().payload().get("message");
        assertThat(message).contains("这个需求会影响哪些部分");

        // 没有发生图变更:节点内容未被触碰,也没有创建新的节点/路由。
        assertThat(commandService.listOperations(project.id())).hasSize(1); // 只有草稿创建这一次
    }

    /**
     * Blocker 7——节点问答捕获锚点的一跳 ACTIVE 语义关系(保留方向),但绝不
     * 递归到被关联节点的邻居,也绝不把被关联节点加入血统。
     */
    @Test
    void nodeQueryProjectsBoundedOneHopSemanticContext() {
        UUID routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        Node a = knowledgeNode; // 已在活动路由血统上
        Node b = commandService.createFloatingDraftNode(project.id(), null, "NOTE",
                Map.of("text", "依赖项 B"));
        Node c = commandService.createFloatingDraftNode(project.id(), null, "NOTE",
                Map.of("text", "B 的下游 C"));

        // A DEPENDS_ON B(锚点是 source);B DEPENDS_ON C 绝不能被拉进来。
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
        // 血统保持纯净:被关联节点绝不在其中。
        assertThat(snapshot.includedNodeIds()).doesNotContain(b.id(), c.id());

        AgentInputSnapshot input = snapshotBuilder.build(snapshot);
        assertThat(input.relations()).contains(new RelationView(a.id(), b.id(), "DEPENDS_ON"));
        assertThat(input.relatedNodes()).anyMatch(ref ->
                ref.nodeId().equals(b.id()) && "OUTGOING".equals(ref.direction()));
        // 被关联节点的真实正文内容到达线上,而不只是 id。
        assertThat(input.relatedNodes())
                .filteredOn(ref -> ref.nodeId().equals(b.id()))
                .singleElement()
                .satisfies(ref -> {
                    assertThat(ref.node().body().text()).isEqualTo("依赖项 B");
                    assertThat(ref.node().kind()).isEqualTo("KNOWLEDGE");
                });
        // 被关联节点是一等公民的允许来源引用。
        assertThat(input.allowedSourceRefs()).contains("node:" + b.id());
        // 线上血统只携带祖先链,绝不含被关联节点。
        assertThat(input.lineage()).extracting(entry -> entry.node().id())
                .contains(a.id()).doesNotContain(b.id(), c.id());
    }

    /**
     * Item 1(深度评审)——直接关联的 RESOURCE 节点必须无需任何工作区扫描,
     * 就能为问答快照暴露可用能力:能力相关性在血统之外同时考虑有界的一跳
     * 关联节点。
     */
    @Test
    void relatedResourceNodeExposesCapabilityWithoutWorkspaceScan() {
        UUID routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        Node a = knowledgeNode; // KNOWLEDGE 血统节点,血统中没有 RESOURCE
        // 一个游离的 RESOURCE 节点直接关联到锚点。
        Node resource = nodeService.createFloatingWorkspaceNode(
                project.id(), NodeKind.RESOURCE, "TEXT",
                Map.of("text", "离线模式外部评审:队列上限 2048"),
                NodeAuthorKind.USER, null);
        nodeRelationRepository.save(new NodeRelation(Ids.random(), project.id(), a.id(), resource.id(),
                NodeRelationType.SUPPORTS, NodeRelation.Origin.USER, NodeRelation.Status.ACTIVE,
                null, null, Instant.now(), null));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, a.id(), "受哪个外部资源约束？");
        assertThat(snapshot.relatedNodeIds()).containsExactly(resource.id());
        // 血统里没有 resource 节点——相关性必须来自被关联节点,
        // 否则这个能力将不可见。
        assertThat(snapshot.includedNodeIds()).doesNotContain(resource.id());

        AgentInputSnapshot input = snapshotBuilder.build(snapshot);
        assertThat(input.availableCapabilities())
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
                .contains(com.specagent.capability.ResourceExtractTextCapability.CAPABILITY_ID);
        assertThat(input.relatedNodes()).singleElement().satisfies(ref -> {
            assertThat(ref.nodeId()).isEqualTo(resource.id());
            assertThat(ref.node().kind()).isEqualTo("RESOURCE");
            assertThat(ref.node().body().text()).contains("离线模式外部评审");
        });
    }
}
