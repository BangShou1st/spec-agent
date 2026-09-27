package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
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
 * 文件名:RoutelessNodeQueryIntegrationTest.java
 *
 * 测试目标:无路由 NODE_QUERY 作为一等上下文:在完全没有活动路由的项目里,
 * 一个游离的持久化节点(routeIds=[])可以充当"问 AI"的锚点。ContextSnapshot 以
 * route_id = NULL 持久化,模型可见投影绝不发出 {@code route:null} 来源引用,
 * 查询到达终态 COMPLETED,且查询不改变图。
 *
 * 走真实的 {@code ContextSnapshotRepository} 持久化路径——快照行必须以
 * NULL 路由 id 存活下来。
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
        // 移除活动路由,使项目完全没有活动路由。
        UUID activeRouteId = project.activeRouteId();
        routeService.archiveRoute(project.id(), activeRouteId);
        assertThat(projectRepository.findById(project.id()).orElseThrow().activeRouteId()).isNull();

        // 一个完全不带路由创建的游离持久化规范节点。
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

        // ContextSnapshot 以 route_id = NULL 持久化,且包含锚点节点。
        ContextSnapshot snapshot = snapshotRepository.findById(run.contextSnapshotId()).orElseThrow();
        assertThat(snapshot.routeId()).isNull();
        assertThat(snapshot.includedNodeIds()).contains(floatingNode.id());
        // 锚点血统就是那个游离节点本身。
        assertThat(snapshot.includedNodeIds()).hasSize(1);
        // 持久化的行确实携带 NULL route_id。
        UUID persistedRouteId = jdbcTemplate.queryForObject(
                "SELECT route_id FROM context_snapshots WHERE id = ?", UUID.class, snapshot.id());
        assertThat(persistedRouteId).isNull();

        // 模型可见投影绝不发出 route:null 来源引用,
        // 且携带显式的无路由上下文。
        AgentInputSnapshot projected = snapshotBuilder.build(snapshot);
        assertThat(projected.routeId()).isNull();
        assertThat(projected.routeContext().routeId()).isNull();
        assertThat(projected.allowedSourceRefs())
                .noneMatch(ref -> ref.startsWith("route:"));

        // 恰好一次 DECISION 调用;终态 RESPOND 消息存在。
        var phases = eventService.findByRunId(runId);
        assertThat(phases.stream()
                .filter(e -> "DECISION_STARTED".equals(e.eventType())).count()).isEqualTo(1);
        assertThat(phases.stream()
                .anyMatch(e -> NodeQueryService.RESPOND_MESSAGE_EVENT.equals(e.eventType())))
                .isTrue();

        // 查询从未变更图:routes、nodes、answers、relations 与之前完全一致。
        assertThat(countRoutes(project.id())).isEqualTo(routesBefore);
        assertThat(countNodes(project.id())).isEqualTo(nodesBefore);
        assertThat(countAnswers(project.id())).isEqualTo(answersBefore);
        assertThat(countRelations(project.id())).isEqualTo(relationsBefore);
        // 问答 run 自身绝不能碰操作日志:日志中的操作都发生在它之前
        // (setup 归档了活动路由并创建了游离草稿——两者按设计都会入日志)。
        int operationsBeforeRun = commandService.listOperations(project.id()).size();
        assertThat(operationsBeforeRun).isEqualTo(2);
        assertThat(commandService.listOperations(project.id())).hasSize(operationsBeforeRun);
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