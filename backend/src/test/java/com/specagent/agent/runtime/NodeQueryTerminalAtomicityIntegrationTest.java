package com.specagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTerminalizationService;
import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ObservationView;
import com.specagent.agent.protocol.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:NodeQueryTerminalAtomicityIntegrationTest.java
 *
 * 测试目标:NodeQuery 终态结果在真实数据库上的原子性/可见性回归。POLICY_DENIED
 * (及 MUTATION_NOT_CONFIRMABLE)语义事件与 run 的 COMPLETED 转换必须在同一个事务中
 * 提交,使结果 API 绝不可能瞬态地返回 COMPLETED 而缺失必需的语义事件。brain 被打桩
 * (确定性提案);所有持久化与事务行为都是真实服务层跑在真实 PostgreSQL 上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NodeQueryTerminalAtomicityIntegrationTest {

    private static final String TEST_DB_URL =
            "jdbc:postgresql://localhost:5434/spec_agent_test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentRunEventService eventService;
    @Autowired private AgentRunTerminalizationService terminalizationService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AgentRunFailureService agentRunFailureService;

    @MockBean
    private AgentDecisionEngine decisionEngine;

    private Project project;
    private UUID routeId;
    private Node anchor;
    private Node tip;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("节点问答终态测试-" + UUID.randomUUID());
        routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        anchor = commandService.createRootDraftNode(
                project.id(), routeId, "REQUIREMENT", Map.of("text", "锚点需求"));
        // 一个续跑使锚点不再是 tip,这是 NOT_CONFIRMABLE 的前置条件
        // (append-only 续跑要求锚点 == 活动 tip)。
        tip = commandService.appendContinuation(
                        project.id(), routeId, anchor.id(), "REQUIREMENT",
                        Map.of("text", "末端节点"))
                .node();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update(
                "DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                        + "(SELECT id FROM agent_runs WHERE project_id = ?)",
                project.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN "
                + "(SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN "
                + "(SELECT id FROM routes WHERE project_id = ?)", project.id());
        // routes 必须先于 nodes 删除:分支/续跑路由通过
        // branch_at_node_id / root / tip 外键引用节点。
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    /** 对任意 DECISION 调用打桩,返回一个确定性提案。 */
    private void stubDecision(String family, Map<String, Object> payload) {
        when(decisionEngine.runDecision(any(AgentRequestEnvelope.class)))
                .thenAnswer(invocation -> {
                    AgentRequestEnvelope request = invocation.getArgument(0);
                    AgentInputSnapshot snapshot = request.snapshot();
                    return new AgentResponseEnvelope(
                            AgentProtocol.DECISION_PROTOCOL_VERSION,
                            request.runId(),
                            null,
                            new ObservationView(
                                    List.of("The node context grounds the answer."),
                                    List.of(), List.of(), List.of()),
                            new ActionProposal(
                                    family, payload,
                                    UUID.fromString(snapshot.snapshotId()),
                                    snapshot.contextHash(),
                                    List.of(),
                                    UUID.randomUUID(),
                                    request.runId().toString(),
                                    List.of()),
                            new UsageView(1, List.of()),
                            Map.of());
                });
    }

    /** 通过生产 worker 执行一个排队的节点问答 run。 */
    private AgentRun executeQuery(UUID queryRunId) {
        AgentRun claimed = runService.claimNodeQueryRun(queryRunId)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued node-query run " + queryRunId));
        worker.executeRun(claimed);
        return agentRunService.getRun(queryRunId).orElseThrow();
    }

    private String resultStatus(UUID runId) throws Exception {
        return new ObjectMapper()
                .readTree(mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                                project.id(), anchor.id(), runId))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("status").asText();
    }

    // ------------------------------------------------------------------
    // 语义终态事件与 COMPLETED 必须同时可见
    // ------------------------------------------------------------------

    @Test
    void policyDeniedAndCompletedCommitAtomicallyThroughTheWorker() throws Exception {
        stubDecision("GENERATE_ARTIFACT", Map.of());
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "这个动作允许吗？");
        AgentRun run = executeQuery(runId);

        // run 已 COMPLETED 且持久化的语义事件并存——结果 API 在这条路径上
        // 绝不报告 COMPLETED。
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> NodeQueryService.POLICY_DENIED_EVENT.equals(e.eventType())))
                .isTrue();
        assertThat(resultStatus(runId)).isEqualTo("POLICY_DENIED");
    }

    @Test
    void notConfirmableAndCompletedCommitAtomicallyThroughTheWorker() throws Exception {
        // 锚定在非 tip 节点上的 CREATE_NODE 不可能产出可接受的提案
        // (append-only 要求锚点 == tip)。
        stubDecision("CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE", "subtype", "RISK",
                "content", Map.of("text", "结论")));
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "变成结论节点？");
        AgentRun run = executeQuery(runId);

        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> NodeQueryService.MUTATION_NOT_CONFIRMABLE_EVENT.equals(e.eventType())))
                .isTrue();
        assertThat(resultStatus(runId)).isEqualTo("NOT_CONFIRMABLE");
    }

    // ------------------------------------------------------------------
    // 数据库级别的确定性单次提交证明
    // ------------------------------------------------------------------

    /**
     * 在未提交的事务内部,状态写入与语义事件写入对独立连接(READ_COMMITTED)
     * 在单次提交之前不可见。这正是"有 COMPLETED 却没有事件"不可观测的性质:
     * 两者一起对外可见。
     */
    @Test
    void terminalizationWritesAreInvisibleUntilTheSingleCommit() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "可见性？");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            terminalizationService.completeWithEvent(runId, AgentRunStatus.COMPLETED,
                    "trace", AgentRunPhase.COMPLETED,
                    NodeQueryService.POLICY_DENIED_EVENT,
                    Map.of("denyReason", "denied", "actionFamily", "CREATE_NODE"));
            // 另一个物理连接此时对两个写入都不可见。
            String externalStatus = singleQuery(conn -> queryStatus(conn, runId));
            assertThat(externalStatus)
                    .as("run status must not be externally visible before commit")
                    .isNotEqualTo(AgentRunStatus.COMPLETED.code());
            Boolean externalEvent = singleQuery(conn ->
                    queryEventPresent(conn, runId, NodeQueryService.POLICY_DENIED_EVENT));
            assertThat(externalEvent)
                    .as("semantic event must not be externally visible before commit")
                    .isFalse();
            throw new IllegalStateException("force rollback for visibility proof");
        })).isInstanceOf(IllegalStateException.class);

        // 已回滚:语义事件从未对外可见(run 自身的 RUN_CREATED 事件
        // 与终态化配对无关)。
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isNotEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> NodeQueryService.POLICY_DENIED_EVENT.equals(e.eventType())))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 并发轮询:结果 API 绝不能瞬态返回 COMPLETED
    // ------------------------------------------------------------------

    @Test
    void concurrentPollingNeverObservesCompletedWithoutDeniedEvent() throws Exception {
        stubDecision("GENERATE_ARTIFACT", Map.of());
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "能否自动生成？");
        AgentRun claimed = runService.claimNodeQueryRun(runId).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicBoolean sawTerminal = new AtomicBoolean(false);
        try {
            pool.submit(() -> worker.executeRun(claimed));
            // 在 run 执行期间以紧凑循环轮询结果 API。一旦出现终态,它必须是
            // POLICY_DENIED——瞬态的 COMPLETED(缺失语义事件)正是本边界
            // 要封堵的回归。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!sawTerminal.get() && System.nanoTime() < deadline) {
                String observed = resultStatus(runId);
                if (isNonTerminalRunStatus(observed)) {
                    continue;
                }
                assertThat(observed).isEqualTo("POLICY_DENIED");
                sawTerminal.set(true);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(sawTerminal.get()).as("poller must observe the terminal outcome").isTrue();
    }

    private String queryStatus(Connection conn, UUID runId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM agent_runs WHERE id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** run 的中间生命周期状态:轮询器必须继续等待。 */
    private static boolean isNonTerminalRunStatus(String status) {
        return switch (status) {
            case "CREATED", "RUNNING", "CONTEXT_BUILT", "MODEL_CALLED",
                 "REFLECTED", "PERSISTED" -> true;
            default -> false;
        };
    }

    private boolean queryEventPresent(Connection conn, UUID runId, String eventType) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = ?")) {
            ps.setObject(1, runId);
            ps.setString(2, eventType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** 通过全新物理连接执行一次查询(异常不包装直接抛出)。 */
    private <T> T singleQuery(QueryExec<T> query) {
        try (Connection conn = DriverManager.getConnection(
                TEST_DB_URL, "spec_agent", "spec_agent_dev")) {
            return query.run(conn);
        } catch (Exception ex) {
            throw new IllegalStateException("visibility probe failed", ex);
        }
    }

    // ------------------------------------------------------------------
    // 提案与终态化在同一受保护短事务(修复 3 回归)
    // ------------------------------------------------------------------

    /**
     * 检查通过后、提案落库前撤权:决策引擎打桩为"先挂起,等撤权提交后再
     * 返回变异提案",awaiting_approval 路径的受保护短事务在取 task 所有权锁
     * 处被整体拒绝——数据库里没有新增提案、没有 AWAITING_APPROVAL 终态、
     * 没有 PROPOSAL_CREATED/COMPLETED 状态迁移(业务行绝不脱离所有权提交)。
     */
    @Test
    void revocationBetweenCheckAndProposalLeavesNoProposalOrTerminalState() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var revocationDone = new java.util.concurrent.CountDownLatch(1);
        stubDecision("CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE", "subtype", "RISK",
                "content", Map.of("text", "撤权竞态节点")));
        when(decisionEngine.runDecision(any(AgentRequestEnvelope.class))).thenAnswer(invocation -> {
            // 决策"执行中":等控制面撤权提交完成后才返回提案——复现
            // "检查在撤权之前通过、提案在撤权之后落库"的竞态窗口。
            entered.countDown();
            revocationDone.await(15, TimeUnit.SECONDS);
            AgentRequestEnvelope request = invocation.getArgument(0);
            AgentInputSnapshot snapshot = request.snapshot();
            return new AgentResponseEnvelope(
                    AgentProtocol.DECISION_PROTOCOL_VERSION,
                    request.runId(),
                    null,
                    new ObservationView(List.of("context"), List.of(), List.of(), List.of()),
                    new ActionProposal(
                            "CREATE_NODE", Map.of(
                                    "kind", "KNOWLEDGE", "subtype", "RISK",
                                    "content", Map.of("text", "撤权竞态节点")),
                            UUID.fromString(snapshot.snapshotId()),
                            snapshot.contextHash(),
                            List.of(),
                            UUID.randomUUID(),
                            request.runId().toString(),
                            List.of()),
                    new UsageView(1, List.of()),
                    Map.of());
        });
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "撤权竞态下的节点问答");
        AgentRun claimed = runService.claimNodeQueryRun(runId).orElseThrow();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var execution = pool.submit(() -> worker.executeRun(claimed));
            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("the decision call must be in flight before revocation")
                    .isTrue();
            // 控制面撤权:走生产撤权收敛的等价路径(RUN_FAILED + FAILED 终态),
            // 与 WorkspaceAuthorizationRevocationHandler 的收敛事实一致。
            agentRunFailureService.fail(runId, "failed:AUTHORIZATION_REVOKED",
                    com.specagent.access.AccessDeniedException.forbidden(
                            com.specagent.access.AccessReason.PROJECT_CAPABILITY_MISSING, "revoked in test"));
            revocationDone.countDown();
            assertThatThrownBy(execution::get)
                    .as("the awaiting_approval transaction must be refused after revocation")
                    .hasCauseInstanceOf(Exception.class);
        } finally {
            pool.shutdownNow();
        }

        // 无新增提案:变异提案绝不脱离所有权落库。
        Integer proposals = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_proposals WHERE run_id = ?", Integer.class, runId);
        assertThat(proposals).as("no proposal row may survive a post-check revocation").isZero();
        // run 已被控制面撤权收敛为 FAILED,绝不被迟到的 AWAITING_APPROVAL/
        // COMPLETED 覆盖。
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("failed");
        Integer awaitingEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'AWAITING_APPROVAL'",
                Integer.class, runId);
        assertThat(awaitingEvents).isZero();
        Integer proposalCreatedEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'PROPOSAL_CREATED'",
                Integer.class, runId);
        assertThat(proposalCreatedEvents).isZero();
    }

    @FunctionalInterface
    private interface QueryExec<T> {
        T run(Connection conn) throws Exception;
    }
}