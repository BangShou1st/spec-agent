package com.specagent.agent.action;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.answer.AnswerService;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.UndoRedoService;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-database concurrency proofs for the auto-execute graph-action mutation
 * boundary ({@link AgentGraphMutationService}). The auto-execute path (agent
 * REQUEST_USER_INPUT / CREATE_NODE through {@link ProposalActionExecutor})
 * must serialize against every other project-wide graph writer under the
 * project-row lock, re-verify its decision anchor against the CURRENT route
 * tip, and commit node insert + tip update atomically. No mocks: every test
 * races real service-layer transactions over real PostgreSQL row locks.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentAutoExecuteMutationIntegrationTest {

    private static final String AUTO_QUESTION = "自动执行追加的节点问题?";

    @Autowired private ProposalActionExecutor executor;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private UndoRedoService undoRedoService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteService routeService;
    @Autowired private NodeService nodeService;
    @Autowired private AnswerService answerService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private Project project;
    private Route route;
    private Node tip;
    private int baselineChildren;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("自动执行边界测试-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        tip = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "REQUIREMENT", Map.of("text", "根节点"));
        baselineChildren = childrenOf(tip.id());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN "
                + "(SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        // Routes must go before nodes: branch/continuation routes reference
        // nodes via branch_at_node_id / root / tip foreign keys.
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    /** One racer's outcome: returned produced node id, or the exact exception. */
    private record Attempt(boolean success, UUID producedNodeId, Throwable error) {
        static Attempt run(Callable<UUID> action) {
            try {
                return new Attempt(true, action.call(), null);
            } catch (Throwable t) {
                return new Attempt(false, null, t);
            }
        }
    }

    /**
     * Starts two racers behind a shared barrier so both reach the service at
     * the same moment; the database row lock decides the order, not the test.
     */
    private Attempt[] race(Callable<UUID> first, Callable<UUID> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startLine = new CyclicBarrier(2);
        try {
            Future<Attempt> f1 = pool.submit(() -> Attempt.run(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return first.call();
            }));
            Future<Attempt> f2 = pool.submit(() -> Attempt.run(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return second.call();
            }));
            return new Attempt[]{f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The production auto-execute entry: an agent REQUEST_USER_INPUT proposal
     * anchored at {@code anchorNodeId} executed through the real executor into
     * the transactional boundary.
     */
    private UUID autoAppend(UUID anchorNodeId) {
        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", AUTO_QUESTION,
                        "options", List.of(Map.of("label", "选项")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + anchorNodeId));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(),
                UUID.randomUUID(), anchorNodeId, null, null);
        ActionResult result = executor.execute(proposal, context);
        return result.producedNodeId();
    }

    private int childrenOf(UUID nodeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE parent_node_id = ?",
                Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    private long agentAutoNodes() {
        return nodeService.listProject(project.id()).stream()
                .filter(node -> AUTO_QUESTION.equals(node.question()))
                .count();
    }

    // ------------------------------------------------------------------
    // A. auto-execute REQUEST_USER_INPUT vs concurrent Undo
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentUndoNeverLeavesDanglingState() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    undoRedoService.undo(project.id());
                    return null;
                });
        Attempt auto = attempts[0];
        Node rootNow = nodeService.getNode(tip.id()).orElseThrow();
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // The agent append committed first: the undo of the root draft
            // must be rejected (the agent node is a live child), and nothing
            // is half-applied.
            assertThat(attempts[1].error()).as("undo must be rejected while a live child exists")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(rootNow.isRetracted()).isFalse();
            assertThat(routeNow.tipNodeId()).isEqualTo(auto.producedNodeId());
        } else {
            // The undo committed first (root retracted, route cleared): the
            // agent append must fail closed as stale and insert nothing.
            assertThat(auto.error()).isInstanceOf(StaleProposalException.class);
            assertThat(rootNow.isRetracted()).isTrue();
            assertThat(routeNow.tipNodeId()).isNull();
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
            assertThat(agentAutoNodes()).isZero();
        }
        // No dangling half-state: an undone root can never keep an appended
        // agent child as the route tip.
        if (rootNow.isRetracted()) {
            assertThat(routeNow.tipNodeId()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // B. auto-execute append vs concurrent continuation
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentContinuationHasSingleSerializedOrdering() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    graphCommandService.appendContinuation(
                            project.id(), route.id(), tip.id(), "NOTE",
                            Map.of("text", "用户续写"));
                    return null;
                });
        Attempt auto = attempts[0];
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // The auto-execute won the anchor position: its node is the tip
            // of the original route. The continuation must never have
            // overwritten that tip with its own node on the same route.
            assertThat(routeNow.tipNodeId()).isEqualTo(auto.producedNodeId());
        } else {
            // The continuation won the anchor position first; the auto-execute
            // must fail closed as stale and never insert against the moved tip.
            assertThat(auto.error()).isInstanceOf(StaleProposalException.class);
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
            assertThat(agentAutoNodes()).isZero();
        }
    }

    @Test
    void staleAutoExecuteAfterContinuationNeverOverwritesTheNewerTip() {
        Node cont = graphCommandService.appendContinuation(
                        project.id(), route.id(), tip.id(), "NOTE", Map.of("text", "用户先续写"))
                .node();

        // The agent decision anchored at the OLD tip, which is no longer the
        // tip: the append must fail closed and leave exactly the newer node.
        assertThatThrownBy(() -> autoAppend(tip.id()))
                .isInstanceOf(StaleProposalException.class);
        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren + 1);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(cont.id());
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // C. auto-execute append vs archive source route
    // ------------------------------------------------------------------

    @Test
    void autoExecuteVsConcurrentArchiveNeverWritesToArchivedRoute() throws Exception {
        Attempt[] attempts = race(
                () -> autoAppend(tip.id()),
                () -> {
                    routeService.archiveRoute(project.id(), route.id());
                    return null;
                });
        Attempt auto = attempts[0];
        Route routeNow = routeRepository.findById(route.id()).orElseThrow();

        if (auto.success()) {
            // The append won first; the route then archived with the new tip.
            assertThat(routeNow.lifecycleStatus().code()).isEqualTo("archived");
            assertThat(routeNow.tipNodeId()).isEqualTo(auto.producedNodeId());
        } else {
            // The archive won first: an archived route must never receive a
            // later stale agent node.
            assertThat(auto.error())
                    .as("auto-execute against an archived route must fail closed")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not open");
            assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
            assertThat(routeNow.tipNodeId()).isEqualTo(tip.id());
            assertThat(agentAutoNodes()).isZero();
        }
    }

    @Test
    void archivedRouteNeverReceivesALaterStaleNode() {
        routeService.archiveRoute(project.id(), route.id());

        assertThatThrownBy(() -> autoAppend(tip.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tip.id());
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // D. failure after node INSERT but before route-tip update rollback
    // ------------------------------------------------------------------

    @Test
    void failureAfterNodeInsertRollsBackTheNodeToo() {
        UUID tipBefore = routeRepository.findById(route.id()).orElseThrow().tipNodeId();

        // The mutation boundary runs the whole append (node INSERT + tip/root
        // update) inside ONE transaction. A failure injected AFTER the node is
        // inserted — here a forced rollback before the transaction commits —
        // must roll the inserted node back together with the tip update: the
        // node is never observable without the tip advancement.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            autoAppend(tip.id());
            throw new IllegalStateException("injected failure after node insert");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(childrenOf(tip.id())).isEqualTo(baselineChildren);
        assertThat(routeRepository.findById(route.id()).orElseThrow().tipNodeId())
                .isEqualTo(tipBefore);
        assertThat(agentAutoNodes()).isZero();
    }

    // ------------------------------------------------------------------
    // E. unanswered INTERACTION Question invariant (no child before answer)
    // ------------------------------------------------------------------

    private Project newQuestionProject(String title, Node[] questionOut) {
        Project p = projectService.createProject(title + "-" + UUID.randomUUID());
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();
        Node q1 = nodeService.createRootNode(p.id(), r.id(),
                "未回答的澄清问题 Q1?", "purpose",
                List.of(new NodeOption(UUID.randomUUID(), "选项A", null)), true);
        questionOut[0] = q1;
        return p;
    }

    @Test
    void unansweredInteractionQuestionRejectsAgentChildAndKeepsTip() {
        // Real integration: create project/route, create an INTERACTION Question
        // Q1 as route root, do NOT finalize an Answer for Q1, then attempt a
        // real ProposalActionExecutor REQUEST_USER_INPUT anchored at Q1.
        Node[] q1Box = new Node[1];
        Project p = newQuestionProject("未回答问题拒绝追加", q1Box);
        Node q1 = q1Box[0];
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();
        int childrenBefore = childrenOf(q1.id());
        long autoBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ? AND parent_node_id = ?",
                Long.class, p.id(), q1.id());
        UUID tipBefore = r.tipNodeId();

        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", "跟进问题 Q2?",
                        "purpose", "follow-up",
                        "options", List.of(Map.of("label", "选项B")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + q1.id()));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), p.id(), r.id(), UUID.randomUUID(), q1.id(), null, null);

        assertThatThrownBy(() -> executor.execute(proposal, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNANSWERED_QUESTION_HAS_CHILD");

        Route routeNow = routeRepository.findById(r.id()).orElseThrow();
        assertThat(routeNow.tipNodeId()).isEqualTo(q1.id()).isEqualTo(tipBefore);
        assertThat(childrenOf(q1.id())).isEqualTo(childrenBefore);
        long autoAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nodes WHERE project_id = ? AND parent_node_id = ?",
                Long.class, p.id(), q1.id());
        assertThat(autoAfter).isEqualTo(autoBefore);
        assertThat(agentAutoNodesIn(p.id())).isZero();

        // Cleanup isolated project
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", p.id());
    }

    @Test
    void finalizedAnswerAllowsAgentQuestionProgression() {
        Node[] q1Box = new Node[1];
        Project p = newQuestionProject("已回答后允许追问", q1Box);
        Node q1 = q1Box[0];
        Route r = routeRepository.findById(p.activeRouteId()).orElseThrow();

        answerService.finalizeAnswer(p.id(), r.id(), q1.id(), null, "已回答 Q1", "test-user");

        ActionProposal proposal = new ActionProposal(
                "REQUEST_USER_INPUT",
                Map.of("questionText", "跟进问题 Q2?",
                        "purpose", "follow-up",
                        "options", List.of(Map.of("label", "选项B")),
                        "allowFreeAnswer", true),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + q1.id()));
        ActionExecutionContext context = new ActionExecutionContext(
                UUID.randomUUID(), p.id(), r.id(), UUID.randomUUID(), q1.id(), null, null);

        ActionResult result = executor.execute(proposal, context);
        Node q2 = nodeService.getNode(result.producedNodeId()).orElseThrow();

        assertThat(q2.parentNodeId()).isEqualTo(q1.id());
        Route routeNow = routeRepository.findById(r.id()).orElseThrow();
        assertThat(routeNow.tipNodeId()).isEqualTo(q2.id());
        assertThat(childrenOf(q1.id())).isEqualTo(1);

        // Cleanup isolated project
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", p.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", p.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", p.id());
    }

    private long agentAutoNodesIn(UUID projectId) {
        return nodeService.listProject(projectId).stream()
                .filter(node -> "跟进问题 Q2?".equals(node.question()))
                .count();
    }
}
