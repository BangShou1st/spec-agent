package com.specagent.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ExecutorLeaseLossIntegrationTest.java
 *
 * 测试目标:执行器租约丢失的真实检测(第二轮复核 R2-B 的闭合)。旧实现
 * 的 owned()/assertOwned() 只读内存布尔值——租约的物理连接被终止后,
 * 新执行器能取得数据库锁,而旧执行器仍自认持有所有权。
 *
 * 本测试对真实 PostgreSQL 执行:
 * - 真正终止租约的数据库会话(pg_terminate_backend,等效数据库重启/
 *   运维 kill 会话),旧执行器必须立即失去所有权并永久闩锁;
 * - 所有权空闲后旧执行器绝不静默重取(重连 ≠ 恢复所有权);
 * - 新执行器在旧执行器失去所有权后可以取得租约;
 * - 新执行器接管后,旧执行器"延迟返回"的持久化写入(checkpoint/
 *   complete)被 fencing 拒绝,而诚实终态化(fail)永远被允许;
 * - 正常释放后可重新获取(崩溃恢复路径)。
 *
 * 全部连接来自测试 DataSource(spec_agent_test),结束后显式释放,
 * 不触碰用户业务库。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutorLeaseLossIntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired AgentRunRepository agentRunRepository;
    @Autowired com.specagent.workspace.project.ProjectService projects;
    @Autowired org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    private final java.util.List<UUID> touchedProjectIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanUpRows() {
        for (UUID projectId : touchedProjectIds) {
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :projectId)",
                    Map.of("projectId", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :projectId",
                    Map.of("projectId", projectId));
        }
    }

    /** 把给定租约作为唯一 fence 源的 AgentRunService(模拟旧执行器进程)。 */
    private AgentRunService fencedService(ExecutorLease lease) {
        return new AgentRunService(agentRunRepository, new ExecutionFence(providerOf(lease), jdbc),
                new org.springframework.transaction.support.TransactionTemplate(txManager));
    }

    private void terminateBackend(int pid) throws SQLException {
        try (Connection admin = dataSource.getConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(" + pid + ")");
        }
    }

    @Test
    void terminatedLeaseSessionIsDetectedAndOldExecutorStopsWriting() throws Exception {
        var first = new ExecutorLease(dataSource, "spec_agent_test");
        assertThat(first.owned()).isTrue();
        assertThat(first.isLost()).isFalse();

        // 第二个执行器在旧执行器健康时被拒绝(单执行器边界)
        assertThatThrownBy(() -> new ExecutorLease(dataSource, "spec_agent_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another executor already owns this database");

        // 真正终止租约的数据库会话(不只是 close,也不只是 destroy())
        terminateBackend(first.getLeasePid());
        Thread.sleep(200); // 让数据库完成会话清理

        // assertOwned 同步验证数据库所有权,绝不使用缓存窗口
        assertThatThrownBy(first::assertOwned)
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThat(first.isLost()).isTrue();
        assertThat(first.getLostReason()).isNotBlank();

        // 所有权现在空闲,但旧执行器绝不静默重取:重连 ≠ 恢复所有权。
        // 等 owned() 的验证缓存窗口过期后仍然为 false,且缓存绝不掩盖丢失。
        Thread.sleep(600);
        assertThat(first.owned()).isFalse();
        assertThatThrownBy(first::assertOwned)
                .isInstanceOf(ExecutorLease.LeaseLostException.class);

        // 新执行器取得所有权(崩溃后所有权可恢复)
        var second = new ExecutorLease(dataSource, "spec_agent_test");
        try {
            assertThat(second.owned()).isTrue();
        } finally {
            second.destroy();
        }
    }

    @Test
    void fencedWritesOfOldExecutorAreRefusedAfterTakeover() throws Exception {
        var first = new ExecutorLease(dataSource, "spec_agent_test");
        var oldService = fencedService(first);

        var p = projects.createProject("Lease fence " + UUID.randomUUID());
        touchedProjectIds.add(p.id());
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(
                runId, p.id(), null,
                AgentRunTriggerType.DECISION_CYCLE, null, null, null, null, null, null,
                AgentRunStatus.CREATED, null, "FENCE_TEST", null, null,
                java.time.Instant.now(), null, null, null, null));

        // 旧执行器正常时可以推进 run
        oldService.markModelCalled(runId, "created");

        // 新执行器接管(等效:旧执行器会话被终止 + 新执行器取锁)
        terminateBackend(first.getLeasePid());
        Thread.sleep(200);
        var second = new ExecutorLease(dataSource, "spec_agent_test");
        try {
            // 旧执行器"延迟返回"的持久化写入被拒绝:FOR SHARE 验证读到新代次
            // (接管已提交),整体拒绝(LeaseLostException)。
            assertThatThrownBy(() -> oldService.markModelCalled(runId, "late"))
                    .isInstanceOf(ExecutorLease.LeaseLostException.class);
            assertThatThrownBy(() -> oldService.complete(
                    runId, AgentRunStatus.COMPLETED, "late-done"))
                    .isInstanceOf(ExecutorLease.LeaseLostException.class);

            // 已闩锁丢失的旧执行器连诚实终态化也不再写入(R4-C:已明确丢锁
            // 后检查点/认领/终态写入均不能继续):ownerEpoch() 在闩锁处抛出。
            // 业务恢复由当前合法所有者负责。
            assertThatThrownBy(() -> oldService.fail(runId, "failed:EXECUTOR_LEASE_LOST"))
                    .as("known-lost owner must not write even an honest fail")
                    .isInstanceOf(ExecutorLease.LeaseLostException.class);
            assertThat(agentRunServiceStatus(runId)).isEqualTo(AgentRunStatus.MODEL_CALLED);

            // 当前合法所有者负责业务恢复:把旧执行器的在途 run 终态化
            var newOwnerService = new AgentRunService(agentRunRepository,
                    new ExecutionFence(providerOf(second), jdbc),
                    new org.springframework.transaction.support.TransactionTemplate(txManager));
            newOwnerService.fail(runId, "failed:new-owner-recovery");
            assertThat(agentRunServiceStatus(runId)).isEqualTo(AgentRunStatus.FAILED);
        } finally {
            second.destroy();
            // 新执行器已释放;清理本测试的 run 行(状态已是终态)
        }
    }

    private org.springframework.beans.factory.ObjectProvider<ExecutorLease> providerOf(ExecutorLease lease) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public ExecutorLease getObject() { return lease; }
            @Override public ExecutorLease getIfAvailable() { return lease; }
            @Override public ExecutorLease getIfUnique() { return lease; }
            @Override public ExecutorLease getObject(Object... args) { return lease; }
        };
    }

    private AgentRunStatus agentRunServiceStatus(UUID runId) {
        return agentRunRepository.findById(runId).orElseThrow().status();
    }

    @Test
    void leaseIsReacquirableAfterCleanRelease() throws Exception {
        var first = new ExecutorLease(dataSource, "spec_agent_test");
        first.destroy();
        assertThat(first.owned()).isFalse();
        assertThatThrownBy(first::assertOwned)
                .isInstanceOf(IllegalStateException.class);
        var second = new ExecutorLease(dataSource, "spec_agent_test");
        try {
            assertThat(second.owned()).isTrue();
        } finally {
            second.destroy();
        }
    }
}
