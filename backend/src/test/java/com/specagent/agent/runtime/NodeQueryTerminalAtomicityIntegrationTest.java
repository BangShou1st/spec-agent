package com.specagent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentRunTerminalizationService;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.UsageView;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
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
 * Real-database atomicity/visibility regression for the NodeQuery terminal
 * outcome. The POLICY_DENIED (and MUTATION_NOT_CONFIRMABLE) semantics event and
 * the run COMPLETED transition must commit in ONE transaction, so the result
 * API can never transiently return COMPLETED while the required semantic event
 * is absent. The brain is stubbed (deterministic proposals); every persistence
 * and transaction behavior is the real service layer over real PostgreSQL.
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
        // A continuation makes the anchor non-tip, which is a NOT_CONFIRMABLE
        // precondition (append-only continuation requires anchor == live tip).
        tip = commandService.appendContinuation(
                        project.id(), routeId, anchor.id(), "REQUIREMENT",
                        Map.of("text", "末端节点"))
                .node();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN "
                + "(SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN "
                + "(SELECT id FROM routes WHERE project_id = ?)", project.id());
        // Routes must go before nodes: branch/continuation routes reference
        // nodes via branch_at_node_id / root / tip foreign keys.
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    /** Stubs the brain to return one deterministic proposal for any DECISION call. */
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

    /** Executes one queued node-query run through the production worker. */
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
    // The semantic terminal event and COMPLETED must be visible together
    // ------------------------------------------------------------------

    @Test
    void policyDeniedAndCompletedCommitAtomicallyThroughTheWorker() throws Exception {
        stubDecision("GENERATE_ARTIFACT", Map.of());
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "这个动作允许吗？");
        AgentRun run = executeQuery(runId);

        // The run is COMPLETED and the durable semantic event exists side by
        // side — the result API never reports COMPLETED for this path.
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> NodeQueryService.POLICY_DENIED_EVENT.equals(e.eventType())))
                .isTrue();
        assertThat(resultStatus(runId)).isEqualTo("POLICY_DENIED");
    }

    @Test
    void notConfirmableAndCompletedCommitAtomicallyThroughTheWorker() throws Exception {
        // A CREATE_NODE anchored at a NON-tip node cannot produce an
        // acceptable proposal (append-only requires anchor == tip).
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
    // Deterministic single-commit proof at the database level
    // ------------------------------------------------------------------

    /**
     * Inside an open transaction the status write and the semantic event write
     * are INVISIBLE to a separate connection (READ_COMMITTED) until the single
     * commit. This is the exact property that makes "COMPLETED without the
     * event" unobservable: both become externally visible together.
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
            // A different physical connection observes NEITHER write yet.
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

        // Rolled back: the semantic event never became visible (the run's own
        // RUN_CREATED event is separate from the terminalization pairing).
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isNotEqualTo(AgentRunStatus.COMPLETED);
        assertThat(eventService.findByRunId(runId).stream()
                .anyMatch(e -> NodeQueryService.POLICY_DENIED_EVENT.equals(e.eventType())))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Concurrent poller: the result API can never transiently return COMPLETED
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
            // Poll the result API in a tight loop while the run executes. The
            // moment a terminal status appears it MUST be POLICY_DENIED — a
            // transient COMPLETED (missing the semantic event) is the exact
            // regression this boundary closes.
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

    /** Intermediate run lifecycle statuses: the poller must keep waiting. */
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

    /** Runs one query against a fresh physical connection (throws unwrapped). */
    private <T> T singleQuery(QueryExec<T> query) {
        try (Connection conn = DriverManager.getConnection(
                TEST_DB_URL, "spec_agent", "spec_agent_dev")) {
            return query.run(conn);
        } catch (Exception ex) {
            throw new IllegalStateException("visibility probe failed", ex);
        }
    }

    @FunctionalInterface
    private interface QueryExec<T> {
        T run(Connection conn) throws Exception;
    }
}