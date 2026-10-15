package com.specagent.agent.runevent;

import com.specagent.common.Ids;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:AgentRunEventConcurrentAppendTest.java
 *
 * 测试目标:同一 run 上的并发事件追加必须得到<b>连续且唯一</b>的序号。
 *
 * <p>原实现用 {@code SELECT COALESCE(MAX(sequence),0)+1 ... FROM agent_run_events}:
 * Read Committed 下两个并发事务会算出同一个序号,后到者撞
 * {@code UNIQUE (run_id, sequence)} 而失败。单执行器时代每个 run 只有一个执行者,
 * 该竞争不可达;多 Worker 下接管窗口内则可能发生,而事件序列是前端进度与恢复
 * 核验的事实源,丢一条或重号都会让读模型失真。
 *
 * <p>用真实 PostgreSQL 并发连接证明:序号无重复、无缺号、行数与写入次数一致。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunEventConcurrentAppendTest {

    @Autowired AgentRunEventService events;
    @Autowired AgentRunEventRepository repository;
    @Autowired ProjectService projects;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID projectId : touchedProjects) {
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :p", Map.of("p", projectId));
        }
        touchedProjects.clear();
    }

    private UUID newRun() {
        Project project = projects.createProject("event append " + UUID.randomUUID());
        touchedProjects.add(project.id());
        UUID runId = Ids.random();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace, created_at, owner_epoch)
                VALUES (:id, :projectId, 'DECISION_CYCLE', 'running', NULL, now(), 0)
                """, Map.of("id", runId, "projectId", project.id()));
        return runId;
    }

    @Test
    void concurrentAppendsToTheSameRunProduceAContiguousSequence() throws Exception {
        UUID runId = newRun();
        int writers = 8;
        int perWriter = 5;
        int total = writers * perWriter;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                int id = writer;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        events.append(runId, AgentRunPhase.EXECUTING,
                                "PROCESS_NOTE", Map.of("writer", id, "index", i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<AgentRunEvent> stored = repository.findByRunId(runId);
        assertThat(stored).as("every append must be persisted, none lost to a unique violation")
                .hasSize(total);
        assertThat(stored.stream().map(AgentRunEvent::sequence).toList())
                .as("the per-run sequence must be contiguous from 1 with no gaps or duplicates")
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, total)
                        .boxed().toList());
        // 顺序严格递增(findByRunId 已按 sequence 排序)。
        for (int i = 1; i < stored.size(); i++) {
            assertThat(stored.get(i).sequence())
                    .isGreaterThan(stored.get(i - 1).sequence());
        }
    }

    @Test
    void appendsToDifferentRunsDoNotSerializeAgainstEachOther() throws Exception {
        // 锁粒度是 run 行:不同 run 的事件追加不应互相阻塞(否则多 Worker 的
        // 并行度会被无谓地串行化)。这里只证明两者都能完成,不测量耗时。
        UUID first = newRun();
        UUID second = newRun();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    events.append(first, AgentRunPhase.EXECUTING, "PROCESS_NOTE", Map.of());
                }
                return null;
            });
            Future<?> b = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    events.append(second, AgentRunPhase.EXECUTING, "PROCESS_NOTE", Map.of());
                }
                return null;
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(repository.findByRunId(first)).hasSize(10);
        assertThat(repository.findByRunId(second)).hasSize(10);
    }
}
