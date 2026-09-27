package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:AgentInputFrozenProjectionConcurrencyIntegrationTest.java
 *
 * 测试目标:T7——并发首次冻结:多个线程投影同一个从未冻结过的 ContextSnapshot,
 * 必须恰好产生一个持久的冻结身份。snapshot_id 上的唯一索引是最终仲裁者;失败者回读
 * 赢家的行(first-writer-wins),既没有重复行,也不会出现 last-writer-wins 式的
 * 冻结载荷变异。刻意不加 {@code @Transactional}:竞争的插入必须在各自连接里提交,
 * 唯一索引才能真正仲裁。
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
