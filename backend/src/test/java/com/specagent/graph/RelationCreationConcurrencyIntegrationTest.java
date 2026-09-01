package com.specagent.graph;

import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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

/**
 * Semantic-relation creation concurrency. Per-project row locking serializes
 * relation creation so two racing transactions both read a stable relation
 * graph:
 *
 * <ul>
 *   <li>causal opposite edges (A DEPENDS_ON B racing B DEPENDS_ON A): exactly
 *       one persists, the loser is rejected as a cycle, and the active causal
 *       DAG stays acyclic — never two edges.</li>
 *   <li>symmetric duplicate (A RELATED_TO B racing B RELATED_TO A): exactly
 *       one persists and the loser is a controlled conflict, never a raw
 *       {@link DuplicateKeyException} / 500.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: the racers run in real
 * independent transactions, so the setup rows are committed first.
 */
@SpringBootTest
@ActiveProfiles("test")
class RelationCreationConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("关系并发 " + UUID.randomUUID());
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        nodeA = commandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "A"));
        nodeB = commandService.appendContinuation(
                project.id(), route.id(), nodeA.id(), "NOTE", Map.of("text", "B")).node();
    }

    @AfterEach
    void cleanUp() {
        if (project == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM agent_run_events "
                + "WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM route_inherited_answers "
                + "WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)", project.id());
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.id());
    }

    /**
     * Causal opposite edges raced concurrently: A DEPENDS_ON B vs B DEPENDS_ON
     * A. The project lock serializes the graph read + cycle validation, so
     * exactly one edge persists and the loser fails as RELATION_DEPENDENCY_CYCLE.
     */
    @Test
    void concurrentOppositeDependencyEdgesKeepExactlyOneAcyclicEdge() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> forward = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeA.id(), nodeB.id(), NodeRelationType.DEPENDS_ON,
                        NodeRelation.Origin.USER, null, null));
            });
            Future<Attempt> reverse = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeB.id(), nodeA.id(), NodeRelationType.DEPENDS_ON,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt attemptA = forward.get(60, TimeUnit.SECONDS);
            Attempt attemptB = reverse.get(60, TimeUnit.SECONDS);

            // Exactly one edge persists.
            assertThat(attemptA.success ^ attemptB.success)
                    .as("exactly one causal relation must be created")
                    .isTrue();
            // The loser is rejected as a cycle — it observed the winner's edge.
            Attempt loser = attemptA.success ? attemptB : attemptA;
            assertThat(loser.error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RELATION_DEPENDENCY_CYCLE");

            // Never two edges; the active causal DAG is a single acyclic edge.
            List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
            assertThat(active).hasSize(1);
            assertThat(active.get(0).relationType()).isEqualTo(NodeRelationType.DEPENDS_ON);
            // The persisted direction is one of the two raced edges.
            assertThat(active.get(0).sourceNodeId()).isIn(nodeA.id(), nodeB.id());
            assertThat(active.get(0).targetNodeId()).isIn(nodeA.id(), nodeB.id());
            assertThat(active.get(0).sourceNodeId()).isNotEqualTo(active.get(0).targetNodeId());
            // Only the winner wrote an operation entry.
            assertThat(commandService.listOperations(project.id())
                    .stream().filter(op -> op.type() == GraphOperation.Type.CREATE_SEMANTIC_RELATION)
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Symmetric duplicate raced in both directions: A RELATED_TO B vs B
     * RELATED_TO A canonicalize to the same endpoints. Exactly one persists
     * and the loser is a controlled conflict — never a raw
     * {@link DuplicateKeyException} (which would surface as HTTP 500).
     */
    @Test
    void concurrentSymmetricDuplicateIsAControlledConflictNever500() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> ab = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeA.id(), nodeB.id(), NodeRelationType.RELATED_TO,
                        NodeRelation.Origin.USER, null, null));
            });
            Future<Attempt> ba = pool.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return Attempt.run(() -> commandService.createSemanticRelation(
                        project.id(), nodeB.id(), nodeA.id(), NodeRelationType.RELATED_TO,
                        NodeRelation.Origin.USER, null, null));
            });

            Attempt attemptAb = ab.get(60, TimeUnit.SECONDS);
            Attempt attemptBa = ba.get(60, TimeUnit.SECONDS);

            // Exactly one persisted.
            assertThat(attemptAb.success ^ attemptBa.success)
                    .as("exactly one symmetric relation must be created")
                    .isTrue();
            // The loser is a controlled duplicate conflict (409 semantics),
            // never a raw DuplicateKeyException (500).
            Attempt loser = attemptAb.success ? attemptBa : attemptAb;
            assertThat(loser.error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
            assertThat(loser.error).isNotInstanceOf(DuplicateKeyException.class);

            List<NodeRelation> active = relationRepository.findActiveByProject(project.id());
            assertThat(active).hasSize(1);
            assertThat(active.get(0).relationType()).isEqualTo(NodeRelationType.RELATED_TO);
            // Canonicalized to one endpoint order regardless of direction.
            assertThat(active.get(0).sourceNodeId())
                    .isEqualTo(nodeA.id().compareTo(nodeB.id()) <= 0 ? nodeA.id() : nodeB.id());
            assertThat(active.get(0).targetNodeId())
                    .isEqualTo(nodeA.id().compareTo(nodeB.id()) <= 0 ? nodeB.id() : nodeA.id());
            assertThat(commandService.listOperations(project.id())
                    .stream().filter(op -> op.type() == GraphOperation.Type.CREATE_SEMANTIC_RELATION)
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One racer's outcome: success, or the exact exception it failed with. */
    private record Attempt(boolean success, Throwable error) {
        static Attempt run(Callable<?> action) {
            try {
                action.call();
                return new Attempt(true, null);
            } catch (Throwable t) {
                return new Attempt(false, t);
            }
        }
    }
}