package com.specagent.agent.runtime;

import com.specagent.common.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ExecutionFenceAtomicityBarrierTest.java
 *
 * 测试目标:执行器写入隔离的<strong>事务所有权协议</strong>(第四轮复核
 * R4-A/R4-C 的闭合)。
 *
 * 第四轮复核的两个真实数据库反例:
 * <ol>
 *   <li><strong>R4-A(快照子查询不互斥)</strong>:旧执行器的业务事务写入
 *       未提交产物并发出检查点 UPDATE,UPDATE 在 PostgreSQL 内等待任务行锁
 *       (pg_stat_activity 可见);期间新执行器接管并提交 epoch=2;释放行锁
 *       后,旧 UPDATE 依据语句开始时的快照通过 epoch 条件,旧事务连同未提交
 *       产物成功提交。单条条件 UPDATE 的语句内子查询读不到接管的提交。</li>
 *   <li><strong>R4-C(已知丢锁仍可写)</strong>:租约会话被终止、
 *       assertOwned 已永久闩锁,但尚无新执行器接管——全局 epoch 未变,
 *       ownerEpoch() 直接返回旧代次,检查点写入仍然成功。</li>
 * </ol>
 *
 * 新协议:业务写入事务在事务内、任何业务行之前,对 executor_ownership 行取
 * FOR SHARE 并验证代次(锁保持到提交,与接管的代次递增互斥);已知丢锁在
 * 一切写入入口立即拒绝。因此只有两种可接受顺序:旧事务先完成、接管等待
 * (顺序 A);或接管先提交、旧事务验证新代次后整体拒绝(顺序 B)。禁止的
 * 结果——新代次已提交、旧事务仍按旧快照成功提交——不可能出现。
 *
 * 全部场景使用真实 PostgreSQL 锁状态与同步屏障证明交错(不靠 sleep 时序),
 * 运行在隔离测试库(spec_agent_test),结束后清理测试行,不触碰用户业务库。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionFenceAtomicityBarrierTest {

    @Autowired DataSource dataSource;
    @Autowired AgentRunRepository agentRunRepository;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired Json json;
    @Autowired PlatformTransactionManager txManager;
    @Autowired com.specagent.workspace.project.ProjectService projects;
    @Autowired com.specagent.agent.runevent.AgentRunEventService eventService;
    private final java.util.List<UUID> touchedProjectIds = new java.util.ArrayList<>();
    private final java.util.List<ExecutorLease> leases = new java.util.ArrayList<>();
    private final ExecutorService worker = Executors.newCachedThreadPool();

    @BeforeEach
    void ensureBusinessEffectTable() {
        // 复核反例的"未提交业务产物"载体:与检查点同一事务的普通业务行。
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS review_business_effect(id uuid PRIMARY KEY)");
    }

    @AfterEach
    void cleanUp() throws Exception {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        for (ExecutorLease lease : leases) {
            try {
                lease.destroy();
            } catch (Exception expectedAfterLoss) {
                // 丢锁后的 destroy 释放会失败——所有权已不在本进程手中。
            }
        }
        leases.clear();
        jdbc.getJdbcTemplate().update("DELETE FROM review_business_effect");
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

    /**
     * 在所有权 FOR SHARE 取锁之前暂停的 fence:暂停点位于业务事务的第一条
     * 数据库语句之前——接管可以在暂停期间完成(顺序 B 的确定性前提)。
     */
    static class PausingFence extends ExecutionFence {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        PausingFence(ObjectProvider<ExecutorLease> provider, NamedParameterJdbcTemplate jdbc) {
            super(provider, jdbc);
        }

        @Override
        public long lockOwnershipForWrite() {
            reached.countDown();
            try {
                if (!proceed.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("barrier timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return super.lockOwnershipForWrite();
        }
    }

    private ObjectProvider<ExecutorLease> providerOf(ExecutorLease lease) {
        return new ObjectProvider<>() {
            @Override public ExecutorLease getObject() { return lease; }
            @Override public ExecutorLease getIfAvailable() { return lease; }
            @Override public ExecutorLease getIfUnique() { return lease; }
            @Override public ExecutorLease getObject(Object... args) { return lease; }
        };
    }

    private AgentRunService service(AgentRunRepository repository, ExecutorLease lease) {
        return new AgentRunService(repository, new ExecutionFence(providerOf(lease), jdbc),
                new TransactionTemplate(txManager));
    }

    private AgentRunService service(ExecutionFence fence) {
        return new AgentRunService(agentRunRepository, fence, new TransactionTemplate(txManager));
    }

    private AgentRunService service(ExecutorLease lease) {
        return service(new ExecutionFence(providerOf(lease), jdbc));
    }

    /**
     * 生产中 AgentRunFailureService.fail 以 REQUIRES_NEW 运行(由 Spring
     * 代理提供)。测试里手工构造的实例没有代理,这里用 REQUIRES_NEW 传播的
     * TransactionTemplate 精确复现同一事务边界;代理本身的生效由走真实
     * worker 的集成测试覆盖。
     */
    private AgentRunFailureService failureService(ExecutionFence fence) {
        return new AgentRunFailureService(agentRunRepository, fence, eventService);
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private UUID newRun(String label) {
        var p = projects.createProject(label + " " + UUID.randomUUID());
        touchedProjectIds.add(p.id());
        UUID runId = UUID.randomUUID();
        agentRunRepository.save(new AgentRun(
                runId, p.id(), null,
                AgentRunTriggerType.DECISION_CYCLE, null, null, null, null, null, null,
                AgentRunStatus.CREATED, null, label, null, null,
                Instant.now(), null, null, null, null));
        return runId;
    }

    private String statusOf(UUID runId) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
    }

    private String traceOf(UUID runId) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT trace#>>'{}' FROM agent_runs WHERE id = ?", String.class, runId);
    }

    private long globalEpoch() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT epoch FROM executor_ownership WHERE id = 1", Long.class);
    }

    private int runFailedEventCount(UUID runId) {
        Integer count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'RUN_FAILED'",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private void terminateLeaseSession(ExecutorLease lease) throws SQLException {
        try (Connection admin = dataSource.getConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(" + lease.getLeasePid() + ")");
        }
    }

    /** 等待目标 SQL 真正进入 PostgreSQL 的行锁等待(与复核反例同一判定)。 */
    private void awaitSqlWaitingInsidePostgres(String databaseName, String sqlPattern)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection admin = dataSource.getConnection();
                 PreparedStatement ps = admin.prepareStatement(
                         "SELECT EXISTS(SELECT 1 FROM pg_stat_activity "
                                 + "WHERE datname = ? AND wait_event_type = 'Lock' "
                                 + "AND query ILIKE ?)")) {
                ps.setString(1, databaseName);
                ps.setString(2, sqlPattern);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("SQL never reached the in-database row-lock wait: " + sqlPattern);
    }

    /**
     * 顺序 B 的确定性证明(场景 1 + 场景 3):旧业务事务在第一条语句之前
     * 暂停 → 接管完成(新代次提交)→ 放行 → 旧事务的 FOR SHARE 验证读到
     * 新代次 → 整体拒绝,含未提交业务产物在内的所有写入一并回滚。
     */
    @Test
    void takeoverCommittedBeforeLockMeansOldTransactionIsRejectedWithItsBusinessEffects()
            throws Exception {
        ExecutorLease first = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(first);
        PausingFence pausingFence = new PausingFence(providerOf(first), jdbc);
        AgentRunService oldService = service(pausingFence);

        UUID runId = newRun("BARRIER-ORDER-B");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, first.epoch()))
                .as("old owner claims the run while holding the lease")
                .isPresent();

        TransactionTemplate businessTx = new TransactionTemplate(txManager);
        UUID effectId = UUID.randomUUID();
        Future<?> oldWrite = worker.submit(() -> businessTx.executeWithoutResult(status -> {
            // 未提交业务产物与检查点在同一事务(生产协议)
            jdbc.getJdbcTemplate().update(
                    "INSERT INTO review_business_effect(id) VALUES (?)", effectId);
            oldService.markModelCalled(runId, "old-writer-order-b");
        }));

        // 暂停发生在任何数据库语句(含 FOR SHARE 取锁)之前
        assertThat(pausingFence.reached.await(10, TimeUnit.SECONDS)).isTrue();

        // 接管在旧事务持锁之前完成——新代次已提交
        terminateLeaseSession(first);
        Thread.sleep(200);
        ExecutorLease second = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(second);
        assertThat(second.epoch()).as("takeover strictly increases the ownership epoch")
                .isGreaterThan(first.epoch());

        pausingFence.proceed.countDown();
        assertThatThrownBy(oldWrite::get)
                .as("old transaction is rejected as a whole after takeover")
                .hasCauseInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM review_business_effect WHERE id = ?", Integer.class, effectId))
                .as("uncommitted business effect rolls back with the rejected transaction")
                .isZero();
        assertThat(statusOf(runId)).isEqualTo("running");
    }

    /**
     * 顺序 A 的确定性证明(场景 2 + 场景 3,第四轮反例 A 的原始交错):
     * 旧业务事务持有 FOR SHARE,检查点 UPDATE 在 PostgreSQL 内等待任务行锁
     * (pg_stat_activity 确认,不是暂停在 Java 里);此刻接管的代次递增
     * 必须被 FOR SHARE 挡住——不可能在旧事务提交之前完成;旧事务先提交
     * (业务产物 + 检查点),接管随后才提交新代次。禁止的结果——新代次已
     * 提交而旧事务仍按旧快照成功提交——被证明不可能。
     */
    @Test
    void checkpointWaitingInsidePostgresBlocksTakeoverUntilOldTransactionCommits()
            throws Exception {
        ExecutorLease first = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(first);
        AgentRunService oldService = service(first);

        UUID runId = newRun("BARRIER-ORDER-A");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, first.epoch())).isPresent();

        // 外部连接只锁住任务行,不改变内容(与复核反例完全一致)
        Connection blocker = dataSource.getConnection();
        blocker.setAutoCommit(false);
        try (PreparedStatement ps = blocker.prepareStatement(
                "SELECT id FROM agent_runs WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, runId);
            ps.executeQuery().close();
        }

        // 旧业务事务:第一条语句取所有权 FOR SHARE,写入业务产物 + 真实检查
        // 点(同事务,与生产 Answer/补丁/快照事务门一致的顺序)
        TransactionTemplate businessTx = new TransactionTemplate(txManager);
        UUID effectId = UUID.randomUUID();
        ExecutionFence oldFence = new ExecutionFence(providerOf(first), jdbc);
        Future<?> oldWrite = worker.submit(() -> businessTx.executeWithoutResult(status -> {
            oldFence.lockOwnershipForWrite();
            jdbc.getJdbcTemplate().update(
                    "INSERT INTO review_business_effect(id) VALUES (?)", effectId);
            oldService.markModelCalled(runId, "old-writer-order-a");
        }));

        // 确定性屏障:检查点 UPDATE 已在 PostgreSQL 内等待行锁
        awaitSqlWaitingInsidePostgres("spec_agent_test", "%UPDATE agent_runs%");

        // 接管的代次递增此刻必须被 FOR SHARE 挡住:短超时探测必然锁超时
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(false);
            try (Statement s = probe.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '800ms'");
                SQLException blocked = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> s.executeUpdate(
                                "UPDATE executor_ownership SET epoch = epoch + 1, "
                                        + "updated_at = now() WHERE id = 1"),
                        SQLException.class);
                if (blocked == null) {
                    throw new AssertionError(
                            "takeover epoch bump did NOT block on the old transaction's "
                                    + "FOR SHARE lock — the forbidden interleaving is reachable");
                }
            } finally {
                probe.rollback();
            }
        }
        assertThat(globalEpoch())
                .as("no takeover can commit while the old business transaction holds FOR SHARE")
                .isEqualTo(first.epoch());

        // 终止旧租约会话:即便所有权已经死亡,业务事务持有的 FOR SHARE 依然
        // 挡住代次递增——接管必须等旧事务结束,不能趁锁等待窗口抢跑。
        terminateLeaseSession(first);
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(false);
            try (Statement s = probe.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '800ms'");
                SQLException stillBlocked = org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> s.executeUpdate(
                                "UPDATE executor_ownership SET epoch = epoch + 1, "
                                        + "updated_at = now() WHERE id = 1"),
                        SQLException.class);
                if (stillBlocked == null) {
                    throw new AssertionError(
                            "epoch bump committed while the old business transaction "
                                    + "was still waiting on the task row lock");
                }
            } finally {
                probe.rollback();
            }
        }

        // 释放任务行锁:旧事务(业务产物 + 检查点)先提交——顺序 A
        blocker.rollback();
        blocker.close();
        oldWrite.get(15, TimeUnit.SECONDS);
        assertThat(statusOf(runId)).isEqualTo("model_called");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM review_business_effect WHERE id = ?", Integer.class, effectId))
                .isEqualTo(1);

        // 旧事务提交之后,接管才能完成
        ExecutorLease second = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(second);
        assertThat(second.epoch()).isGreaterThan(first.epoch());
    }

    /**
     * R4-C 反例(场景 4):租约已明确丢失(闩锁),尚无新执行器接管、全局
     * epoch 未变——检查点、complete、认领、fail 一切写入入口立即拒绝,
     * 不发生任何业务写入,也不追加"本次失败已发生"的事件。
     */
    @Test
    void knownLostLeaseWithoutTakeoverRefusesAllWrites() throws Exception {
        ExecutorLease first = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(first);
        ExecutionFence fence = new ExecutionFence(providerOf(first), jdbc);
        AgentRunService oldService = service(fence);

        UUID runId = newRun("BARRIER-LOST-NO-TAKEOVER");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, first.epoch())).isPresent();
        oldService.markModelCalled(runId, "created:model_called");
        assertThat(statusOf(runId)).isEqualTo("model_called");

        // 租约会话被终止,assertOwned 检测并永久闩锁;尚无接管者
        terminateLeaseSession(first);
        Thread.sleep(200);
        assertThatThrownBy(first::assertOwned)
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThat(first.isLost()).isTrue();
        long epochBefore = globalEpoch();

        // 检查点/终态写入:ownerEpoch() 在闩锁处立即拒绝
        assertThatThrownBy(() -> oldService.markModelCalled(runId, "late-checkpoint"))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThatThrownBy(() -> oldService.attachContext(runId, UUID.randomUUID(), "late-context"))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThatThrownBy(() -> oldService.complete(runId, AgentRunStatus.COMPLETED, "late-complete"))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
        assertThatThrownBy(() -> oldService.fail(runId, "failed:late"))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);

        // 失败服务:所有权拒绝,不写状态、不追加 RUN_FAILED 事件
        AgentRunFailureService failureSvc = failureService(fence);
        AgentRunFailureService.FailureOutcome outcome = requiresNew().execute(tx ->
                failureSvc.fail(runId, "failed:lost-owner", RunFailureReasons.EXECUTOR_LEASE_LOST));
        assertThat(outcome).isEqualTo(AgentRunFailureService.FailureOutcome.OWNERSHIP_REFUSED);
        assertThat(runFailedEventCount(runId))
                .as("no RUN_FAILED event may be appended when the state update did not apply")
                .isZero();

        // 认领入口:RunService 在认领前取所有权锁,已闩锁丢失的执行器不能认领
        assertThatThrownBy(() -> new RunServiceClaimProbe(oldService, agentRunRepository, fence).claim(runId))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);

        // 全局 epoch 未变、run 状态未变:丢锁实例没有留下任何业务效果
        assertThat(globalEpoch()).isEqualTo(epochBefore);
        assertThat(statusOf(runId)).isEqualTo("model_called");
    }

    /** 通过 RunService 同一条认领入口验证认领拒绝(认领入口的协议覆盖)。 */
    static class RunServiceClaimProbe {
        private final RunService runService;
        RunServiceClaimProbe(AgentRunService agentRunService, AgentRunRepository repository,
                             ExecutionFence fence) {
            this.runService = new RunService(agentRunService, repository, null, null, null, null, fence);
        }
        Optional<AgentRun> claim(UUID runId) {
            return runService.claimDecisionCycleRun(runId);
        }
    }

    /**
     * REQUIRES_NEW 失败终态化与外层业务事务的锁组合(场景 7):外层事务持有
     * 所有权 FOR SHARE(生产中即业务写入事务),内层 REQUIRES_NEW 的 fail
     * 再取 FOR SHARE——共享锁之间兼容,必须无自死锁地完成,且状态与事件
     * 原子生效。
     */
    @Test
    void requiresNewFailInsideTransactionHoldingOwnershipLockDoesNotSelfDeadlock()
            throws Exception {
        ExecutorLease lease = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(lease);
        ExecutionFence fence = new ExecutionFence(providerOf(lease), jdbc);
        AgentRunService owner = service(fence);
        AgentRunFailureService failureSvc = failureService(fence);

        UUID runId = newRun("BARRIER-REQUIRES-NEW");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, lease.epoch())).isPresent();

        TransactionTemplate outer = new TransactionTemplate(txManager);
        Future<AgentRunFailureService.FailureOutcome> result = worker.submit(() -> outer.execute(tx -> {
            // 外层业务事务持有所有权 FOR SHARE(生产协议的第一条语句)
            fence.lockOwnershipForWrite();
            // 内层 REQUIRES_NEW:另一个连接再取 FOR SHARE——共享锁兼容
            return requiresNew().execute(inner ->
                    failureSvc.fail(runId, "failed:inner-outcome", RunFailureReasons.EXECUTOR_LEASE_LOST));
        }));
        AgentRunFailureService.FailureOutcome outcome =
                result.get(30, TimeUnit.SECONDS);
        assertThat(outcome).as("REQUIRES_NEW fail must complete under the outer FOR SHARE")
                .isEqualTo(AgentRunFailureService.FailureOutcome.APPLIED);
        assertThat(statusOf(runId)).isEqualTo("failed");
        assertThat(runFailedEventCount(runId))
                .as("state transition and RUN_FAILED event commit atomically")
                .isEqualTo(1);
    }

    /**
     * 重复失败上报(场景 6):第一次 fail(APPLIED)恰好一条 RUN_FAILED
     * 事件;重复 fail 是 ALREADY_TERMINAL no-op——不追加重复事件,不覆盖
     * 已有失败原因;已终态记录不被任何后续 fail 覆盖。
     */
    @Test
    void duplicateFailIsIdempotentAndNeverDuplicatesFailureEvents() throws Exception {
        ExecutorLease lease = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(lease);
        ExecutionFence fence = new ExecutionFence(providerOf(lease), jdbc);
        AgentRunService owner = service(fence);
        AgentRunFailureService failureSvc = failureService(fence);

        UUID runId = newRun("BARRIER-DUP-FAIL");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, lease.epoch())).isPresent();

        AgentRunFailureService.FailureOutcome first = requiresNew().execute(tx ->
                failureSvc.fail(runId, "failed:MODEL_CONTRACT_VIOLATION", "MODEL_CONTRACT_VIOLATION"));
        assertThat(first).isEqualTo(AgentRunFailureService.FailureOutcome.APPLIED);
        assertThat(runFailedEventCount(runId)).isEqualTo(1);

        AgentRunFailureService.FailureOutcome duplicate = requiresNew().execute(tx ->
                failureSvc.fail(runId, "failed:duplicate-report", "MODEL_CONTRACT_VIOLATION"));
        assertThat(duplicate).isEqualTo(AgentRunFailureService.FailureOutcome.ALREADY_TERMINAL);
        assertThat(runFailedEventCount(runId))
                .as("duplicate failure report must not create a second RUN_FAILED event")
                .isEqualTo(1);
        assertThat(traceOf(runId)).contains("MODEL_CONTRACT_VIOLATION");

        // 终态不可被任何写入覆盖(包括当前合法所有者)
        assertThatCode(() -> owner.fail(runId, "failed:after-terminal")).doesNotThrowAnyException();
        assertThat(statusOf(runId)).isEqualTo("failed");
        assertThat(traceOf(runId)).contains("MODEL_CONTRACT_VIOLATION");
        assertThatThrownBy(() -> owner.complete(runId, AgentRunStatus.COMPLETED, "again"))
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
    }

    /**
     * 正常执行路径(场景 5 的一部分):持有租约的执行器完成认领 → 检查点
     * → 终态全链路;终态不可被改写。
     */
    @Test
    void normalExecutionPathWithValidLeaseStillWorks() {
        ExecutorLease lease = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(lease);
        AgentRunService owner = service(lease);

        UUID runId = newRun("NORMAL");
        assertThat(agentRunRepository.claimDecisionCycleRun(runId, lease.epoch())).isPresent();
        owner.attachContext(runId, UUID.randomUUID(), "created:context_built");
        owner.markModelCalled(runId, "created:context_built:model_called");
        owner.markReflected(runId, "created:context_built:model_called:reflected");
        owner.complete(runId, AgentRunStatus.COMPLETED,
                "created:context_built:model_called:reflected");
        assertThat(statusOf(runId)).isEqualTo("completed");

        assertThatCode(() -> owner.fail(runId, "failed:after-complete")).doesNotThrowAnyException();
        assertThat(statusOf(runId)).isEqualTo("completed");
        assertThatThrownBy(() -> owner.complete(runId, AgentRunStatus.FAILED, "again"))
                .as("terminal state cannot be rewritten, not even by the current owner")
                .isInstanceOf(ExecutorLease.LeaseLostException.class);
    }

    /** 真实断连 + 接管竞争 + 正常释放(场景 5)。 */
    @Test
    void disconnectTakeoverCompetitionAndCleanRelease() throws Exception {
        ExecutorLease first = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(first);

        assertThatThrownBy(() -> new ExecutorLease(dataSource, "spec_agent_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another executor already owns this database");

        terminateLeaseSession(first);
        Thread.sleep(200);
        assertThatThrownBy(first::assertOwned).isInstanceOf(ExecutorLease.LeaseLostException.class);

        ExecutorLease second = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(second);
        assertThat(second.owned()).isTrue();
        second.destroy();
        assertThat(second.owned()).isFalse();
    }

    /**
     * 丢锁执行器不能认领新任务(认领条件的代次验证,接管的确定性版本)。
     */
    @Test
    void lostOwnerCannotClaimNewRuns() throws Exception {
        ExecutorLease first = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(first);

        UUID claimedRun = newRun("BARRIER-CLAIM");
        assertThat(agentRunRepository.claimDecisionCycleRun(claimedRun, first.epoch()))
                .as("current owner claims the CREATED run")
                .isPresent();

        terminateLeaseSession(first);
        Thread.sleep(200);
        ExecutorLease second = new ExecutorLease(dataSource, "spec_agent_test");
        leases.add(second);

        // 旧代次的认领落空;新代次可以认领
        UUID freshRun = newRun("BARRIER-CLAIM-AFTER");
        assertThat(agentRunRepository.claimDecisionCycleRun(freshRun, first.epoch()))
                .as("lost owner cannot claim new runs")
                .isEmpty();
        assertThat(agentRunRepository.claimDecisionCycleRun(freshRun, second.epoch())).isPresent();
    }
}
