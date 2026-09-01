package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
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
 * T7 — concurrent first freeze: several threads projecting the SAME never
 * frozen ContextSnapshot must produce exactly one durable frozen identity.
 * The unique index on snapshot_id is the final arbiter; losers read back the
 * winner's row (first-writer-wins), so there is no duplicate row and no
 * last-writer-wins mutation of the frozen payload.
 *
 * <p>Deliberately NOT {@code @Transactional}: the racing inserts must commit
 * in their own connections for the unique index to arbitrate for real.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentInputFrozenProjectionConcurrencyIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentFirstFreezeProducesExactlyOneDurableProjection() throws Exception {
        Project project = projectService.createProject("冻结投影-并发-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "racing"));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "并发首冻");

        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier startLine = new CyclicBarrier(workers);
        try {
            List<Future<AgentInputSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit((Callable<AgentInputSnapshot>) () -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return snapshotBuilder.build(snapshot);
                }));
            }

            List<AgentInputSnapshot> results = new ArrayList<>();
            for (Future<AgentInputSnapshot> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).as("every racer sees the same frozen projection")
                    .allSatisfy(result -> assertThat(result).isEqualTo(results.get(0)));
            assertThat(results.get(0).snapshotId()).isEqualTo(snapshot.id().toString());

            Integer rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_input_projections WHERE snapshot_id = ?",
                    Integer.class, snapshot.id());
            assertThat(rowCount).as("exactly one durable frozen identity").isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbcTemplate.update(
                    "DELETE FROM agent_input_projections WHERE snapshot_id = ?",
                    snapshot.id());
        }
    }
}
