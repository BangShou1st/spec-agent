package com.specagent.agent.runtime;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import com.specagent.runtime.execution.WorkerIdentity;
import com.specagent.common.Ids;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:MultiWorkerSchedulingIntegrationTest.java
 *
 * 测试目标:第一阶段最关键的验收(design §12)——
 * <ol>
 *   <li><b>不同项目并行</b>:两个 Worker 同时认领不同项目的任务,各自成功;
 *       同一项目的第二个任务被范围挡回队列,不阻塞其他项目。</li>
 *   <li><b>同一任务不会被两个 Worker 同时认领</b>:并发认领只有一个有效
 *       token,另一个落空。</li>
 *   <li><b>杀掉一个 Worker 后任务安全接管</b>:租约过期 → 恢复扫描撤销旧
 *       token → 任务重新排队可被新 Worker 认领。</li>
 *   <li><b>旧 Worker 恢复后不能提交结果</b>:接管后旧 token 的一切写入被
 *       护栏拒绝。</li>
 * </ol>
 *
 * <p>用真实 PostgreSQL 与两个独立构造的调度器实例(各自独立的
 * {@link WorkerIdentity},等价于两个 Worker 进程),不使用 sleep 时序之外的
 * 任何模拟。数据自建自清。
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiWorkerSchedulingIntegrationTest {
    @Autowired com.specagent.runtime.execution.RuntimeTaskAuthorization taskAuthorization;

    @Autowired RuntimeTaskRepository tasks;
    @Autowired com.specagent.runtime.execution.RuntimeScopeMutex scopeMutex;
    @Autowired RuntimeExecutionSettings settings;
    /**
     * 画布族的生命周期端口。多 Worker 模式下同时存在画布与 GA 两个实现,
     * 因此必须按具体类型注入,不能按接口单值注入(会歧义)。
     */
    @Autowired WorkspaceTaskLifecyclePort lifecyclePort;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @org.junit.jupiter.api.Test
    void manualEmptyScopeMutexRollsBackBlockedClaimAndScansOtherProject() throws Exception {
        Project held=trackedProject("manual scope");
        Project free=trackedProject("independent scope");
        UUID blockedTask=queuedTask(held.id(),"DECISION_CYCLE");
        UUID freeTask=queuedTask(free.id(),"DECISION_CYCLE");
        var worker=schedulerFor(new WorkerIdentity());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(txManager);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(ignored->{
                assertThat(scopeMutex.tryLock(List.of(RuntimeExecutionProtocol.projectScope(held.id())))).isTrue();
                var next=pool.submit(()->worker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),schedulingOnlyPorts(),resolvers()));
                try {
                    var claim=next.get(2,java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
                    assertThat(claim.taskId()).isEqualTo(freeTask);
                    assertThat(tasks.findById(blockedTask).orElseThrow().state()).isEqualTo(RuntimeTaskState.QUEUED);
                    assertThat(tasks.findById(blockedTask).orElseThrow().executionEpoch()).isZero();
                } catch(Exception ex) {throw new AssertionError("blocked scope must be skipped without waiting",ex);}
            });
            var claim=worker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),schedulingOnlyPorts(),resolvers()).orElseThrow();
            assertThat(claim.taskId()).isEqualTo(blockedTask);
        } finally {
            pool.shutdownNow();
        }
    }

    @AfterEach
    void cleanUp() {
        // 先自愈容量计数(本类直接操作调度表,不总是走终态化收口);再删数据。
        // 顺序很重要:先删绑定行才能让重算把 used 归零。
        for (UUID projectId : touchedProjects) {
            jdbc.update("""
                    DELETE FROM runtime_task_attempts WHERE task_id IN
                      (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
                         (SELECT id FROM agent_runs WHERE project_id = :p))
                    """, Map.of("p", projectId));
            jdbc.update("""
                    DELETE FROM runtime_task_capacity WHERE task_id IN
                      (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
                         (SELECT id FROM agent_runs WHERE project_id = :p))
                    """, Map.of("p", projectId));
            jdbc.update("""
                    DELETE FROM runtime_scope_owners WHERE task_id IN
                      (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
                         (SELECT id FROM agent_runs WHERE project_id = :p))
                    """, Map.of("p", projectId));
            jdbc.update("""
                    DELETE FROM runtime_task_scopes WHERE task_id IN
                      (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
                         (SELECT id FROM agent_runs WHERE project_id = :p))
                    """, Map.of("p", projectId));
            jdbc.update("""
                    DELETE FROM runtime_tasks WHERE workspace_run_id IN
                      (SELECT id FROM agent_runs WHERE project_id = :p)
                    """, Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :p", Map.of("p", projectId));
        }
        touchedProjects.clear();
        // 本类直接操作调度表(不总是走终态化收口),因此显式让容量计数自愈:
        // 占用绑定行已删除,reconcileCapacity 会把 used 重算回 0。这条路径本身
        // 也是生产里防止执行槽被永久吞噬的机制。
        tasks.reconcileCapacity();
    }

    /** 一个独立的调度器实例 == 一个 Worker 进程(独立执行身份)。 */
    private RuntimeTaskScheduler schedulerFor(WorkerIdentity identity) {
        return new RuntimeTaskScheduler(tasks, settings, identity,
                new TransactionTemplate(txManager));
    }

    private Project trackedProject(String title) {
        Project project = projects.createProject(title);
        touchedProjects.add(project.id());
        return project;
    }

    /** 直接插入一个 QUEUED 任务(等价于入队,但不经过完整命令链)。 */
    private UUID queuedTask(UUID projectId, String taskType) {
        UUID runId = Ids.random();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace,
                    created_at, owner_epoch)
                VALUES (:id, :projectId, :trigger, 'created', NULL, now(), 0)
                """, Map.of("id", runId, "projectId", projectId, "trigger", taskType));
        UUID taskId = Ids.random();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, :taskType,
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", taskId, "runId", runId, "taskType", taskType));
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) "
                + "VALUES (:id, :scope)", Map.of("id", taskId, "scope", "project:" + projectId));
        return taskId;
    }

    private List<TaskLifecyclePort> ports() {
        return List.of(lifecyclePort);
    }

    /**
     * 只验证调度事实(认领/代次/容量/范围)时使用的空端口。
     *
     * <p>本测试刻意不驱动真实业务周期:业务终态化与 RUNNING 的推进语义由
     * {@code WorkspaceTaskLifecyclePort} 自己的测试与既有 RunWorker 集成测试
     * 覆盖。这里需要一个不改变业务 Run 状态的生命周期端口,才能在同一业务行上
     * 反复做"接管 → 重新认领"的调度层断言。
     */
    private static final TaskLifecyclePort NO_OP_PORT = new TaskLifecyclePort() {
        @Override
        public RuntimeTaskFamily family() {
            return RuntimeTaskFamily.WORKSPACE;
        }

        @Override
        public void onClaimed(ExecutionClaim claim) {
            // 不改业务状态:只验证调度层。
        }

        @Override
        public void onTerminalized(ExecutionClaim claim, RuntimeTaskState taskState,
                                   String errorCode, String reason) {
            // 不改业务状态:只验证调度层。
        }
    };

    private List<TaskLifecyclePort> schedulingOnlyPorts() {
        return List.of(NO_OP_PORT);
    }

    private List<TaskScopeResolver> resolvers() {
        return List.of(new WorkspaceTaskScopeResolver(tasks));
    }

    @Test
    void twoWorkersClaimDifferentProjectsInParallel() {
        Project first = trackedProject("并行项目 A " + UUID.randomUUID());
        Project second = trackedProject("并行项目 B " + UUID.randomUUID());
        queuedTask(first.id(), "DECISION_CYCLE");
        queuedTask(second.id(), "DECISION_CYCLE");

        RuntimeTaskScheduler workerA = schedulerFor(new WorkerIdentity());
        RuntimeTaskScheduler workerB = schedulerFor(new WorkerIdentity());

        Optional<ExecutionClaim> claimA = workerA.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
        Optional<ExecutionClaim> claimB = workerB.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());

        assertThat(claimA).as("worker A claims one project").isPresent();
        assertThat(claimB).as("worker B claims the other project in parallel").isPresent();
        assertThat(claimA.get().taskId()).isNotEqualTo(claimB.get().taskId());
        assertThat(claimA.get().runId()).isNotEqualTo(claimB.get().runId());
        // 两个范围分别被两个任务持有:不同项目确实并行。
        assertThat(tasks.scopesOf(claimA.get().taskId())).hasSize(1);
        assertThat(tasks.scopesOf(claimB.get().taskId())).hasSize(1);
        assertThat(tasks.scopesOf(claimA.get().taskId()))
                .isNotEqualTo(tasks.scopesOf(claimB.get().taskId()));
    }

    @Test
    void sameProjectStaysSerialButDoesNotBlockOtherProjects() {
        Project busy = trackedProject("同项目串行 " + UUID.randomUUID());
        Project free = trackedProject("其他项目继续 " + UUID.randomUUID());
        UUID firstTaskOfBusy = queuedTask(busy.id(), "DECISION_CYCLE");
        queuedTask(busy.id(), "ANSWER_CYCLE"); // 同项目第二个任务
        UUID freeTask = queuedTask(free.id(), "DECISION_CYCLE");

        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());

        // 第一轮:认领一个(可能是 busy 或 free,取决于排队顺序)。
        Optional<ExecutionClaim> first = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
        assertThat(first).isPresent();

        // 第二轮:第二个 Worker 认领;若第一个占了 busy,它必须拿到 free 的任务
        // ——同项目被占用绝不阻塞其他项目。
        RuntimeTaskScheduler other = schedulerFor(new WorkerIdentity());
        Optional<ExecutionClaim> second = other.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
        assertThat(second).isPresent();
        assertThat(java.util.Set.of(first.get().taskId(), second.get().taskId()))
                .as("both claims are distinct tasks")
                .hasSize(2);

        // 若两次认领落在同一范围上,则同项目串行失败。
        assertThat(tasks.scopesOf(first.get().taskId()))
                .as("the two live claims must hold different scopes")
                .isNotEqualTo(tasks.scopesOf(second.get().taskId()));
        // 正在被占用的项目不能饿死空闲项目:free 的任务必须可被认领。
        assertThat(java.util.Set.of(first.get().taskId(), second.get().taskId()))
                .as("the free project's task must be claimable while the busy project is held")
                .contains(freeTask);
        assertThat(firstTaskOfBusy).isNotNull();
    }

    @Test
    void concurrentClaimsOfOneTaskYieldExactlyOneValidToken() throws Exception {
        Project project = trackedProject("并发认领 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");

        RuntimeTaskScheduler workerA = schedulerFor(new WorkerIdentity());
        RuntimeTaskScheduler workerB = schedulerFor(new WorkerIdentity());

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var futureA = pool.submit(() -> {
                latch.await();
                return workerA.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
            });
            var futureB = pool.submit(() -> {
                latch.await();
                return workerB.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
            });
            latch.countDown();
            Optional<ExecutionClaim> a = futureA.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Optional<ExecutionClaim> b = futureB.get(30, java.util.concurrent.TimeUnit.SECONDS);

            long claimed = java.util.stream.Stream.of(a, b).filter(Optional::isPresent).count();
            assertThat(claimed)
                    .as("exactly one worker may hold the single task")
                    .isEqualTo(1);
            ExecutionClaim winner = a.isPresent() ? a.get() : b.get();
            assertThat(winner.taskId()).isEqualTo(taskId);
            // 数据库里只有一条 attempt,且代次为 1(未被并发递增两次)。
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM runtime_task_attempts WHERE task_id = :id",
                    Map.of("id", taskId), Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT execution_epoch FROM runtime_tasks WHERE id = :id",
                    Map.of("id", taskId), Long.class)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void killedWorkerTaskIsRecoveredAndReclaimedWhileItsLateWritesAreRefused() {
        Project project = trackedProject("接管 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");

        RuntimeTaskScheduler dyingWorker = schedulerFor(new WorkerIdentity());
        Optional<ExecutionClaim> claimed = dyingWorker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), ports(), resolvers());
        assertThat(claimed).isPresent();
        ExecutionClaim oldClaim = claimed.get();
        assertThat(oldClaim.taskId()).isEqualTo(taskId);

        // 杀掉进程的等价物:租约到期(不再心跳)。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", taskId));

        // 仍在运行的实例执行过期恢复:撤销旧 token 并做核验判定。
        RuntimeTaskScheduler survivingWorker = schedulerFor(new WorkerIdentity());
        int recovered = survivingWorker.recoverExpiredLeases(ports(), List.of());
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // 没有 RecoveryAdapter 的 task_type 必须诚实失败(结果不确定),
        // 绝不自动重放。
        String state = jdbc.queryForObject("SELECT state FROM runtime_tasks WHERE id = :id",
                Map.of("id", taskId), String.class);
        assertThat(state)
                .as("a task type without a verified recovery adapter fails honestly")
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM runtime_tasks WHERE id = :id",
                Map.of("id", taskId), String.class))
                .isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT recovery_reason FROM runtime_tasks WHERE id = :id",
                Map.of("id", taskId), String.class))
                .as("a reconciliation record must be queryable")
                .contains(String.valueOf(taskId));

        // 旧 Worker 恢复后不能提交结果:其 token 的一切受保护写入被拒绝。
        assertThat(com.specagent.runtime.execution.ExecutionTokenContext.current()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> survivingGuard().lockOwnedTask(oldClaim.token()))
                .as("the old worker's token must be refused after recovery")
                .isInstanceOf(com.specagent.runtime.execution.TaskOwnershipLostException.class);

        // 容量与范围都已释放,不会泄漏。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM runtime_scope_owners WHERE task_id = :id",
                Map.of("id", taskId), Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM runtime_task_capacity WHERE task_id = :id",
                Map.of("id", taskId), Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT used FROM runtime_capacity_slots WHERE slot_key = 'GLOBAL'",
                Map.of(), Integer.class)).isZero();
    }

    @Test
    void takeoverIncrementsEpochSoOldTokenBecomesInvalid() {
        Project project = trackedProject("代次递增 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");

        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim oldClaim = worker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
                schedulingOnlyPorts(), resolvers()).orElseThrow();

        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", taskId));
        RuntimeTaskScheduler survivor = schedulerFor(new WorkerIdentity());
        survivor.recoverExpiredLeases(schedulingOnlyPorts(), List.of());

        // 恢复判定安全(提供 adapter)时任务重新排队;这里直接驱动重新入队以
        // 单独验证代次递增语义(adapter 的安全判定由 killed-worker 用例覆盖)。
        UUID runId = oldClaim.runId();
        jdbc.update("UPDATE runtime_tasks SET state = 'QUEUED', available_at = now() "
                + "WHERE id = :id", Map.of("id", taskId));
        RuntimeTaskScheduler other = schedulerFor(new WorkerIdentity());
        Optional<ExecutionClaim> newClaim = other.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers());
        assertThat(newClaim).isPresent();
        assertThat(newClaim.get().token().epoch())
                .as("a takeover must strictly increase the execution epoch")
                .isGreaterThan(oldClaim.token().epoch());
        assertThat(newClaim.get().runId()).isEqualTo(runId);
        // 旧 token 立即失效(worker/epoch/lease 三项都已改变)。
        assertThat(survivingGuard().stillOwned(oldClaim.token())).isFalse();
    }

    /**
     * 心跳回归(缺陷修复):执行线程阻塞超过租约时,独立心跳执行器必须持续续租,
     * 任务不得被判为过期。修复前本用例会因租约到期而失败(LEASE_EXPIRED)。
     */
    @Test
    void heartbeatRenewsLeaseWhileExecutionBlocksBeyondLease() throws Exception {
        Project project = trackedProject("心跳续租 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim claim = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();

        // 用短租约把时间压缩:生产默认 60s 租约 / 10s 心跳,这里 2s / 1s,语义相同。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() + interval '2 seconds' "
                + "WHERE id = :id", Map.of("id", taskId));

        RuntimeExecutionSettings shortLease = new RuntimeExecutionSettings();
        shortLease.setLeaseSeconds(2);
        shortLease.setHeartbeatSeconds(1);
        var registry = new com.specagent.runtime.execution.ExecutionHeartbeatRegistry();
        var heartbeat = new com.specagent.runtime.execution.LeaseHeartbeat(worker, registry, shortLease, java.util.List.of());
        registry.register(claim.token());
        try {
            heartbeat.start();
            // 模拟长时间执行:执行线程阻塞 5 秒,远超 2 秒租约。
            Thread.sleep(5000);
            java.sql.Timestamp leaseUntil = jdbc.queryForObject(
                    "SELECT lease_until FROM runtime_tasks WHERE id = :id",
                    Map.of("id", taskId), java.sql.Timestamp.class);
            assertThat(leaseUntil.toInstant())
                    .as("the independent heartbeat must keep the lease alive during a blocking execution")
                    .isAfter(java.time.Instant.now());
            assertThat(registry.consumeOwnershipLost(claim.token()))
                    .as("a healthy heartbeat must not declare ownership lost")
                    .isFalse();
        } finally {
            heartbeat.stop();
            registry.unregister(claim.token());
        }
    }

    /**
     * 心跳失败语义:租约已过期(条件续租必然落空)时,心跳必须判定失去所有权,
     * 摘除登记——过期租约绝不复活。
     */
    @Test
    void heartbeatMarksOwnershipLostWhenLeaseAlreadyExpired() throws Exception {
        Project project = trackedProject("心跳丢失 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim claim = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();

        RuntimeExecutionSettings shortLease = new RuntimeExecutionSettings();
        shortLease.setLeaseSeconds(2);
        shortLease.setHeartbeatSeconds(1);
        var registry = new com.specagent.runtime.execution.ExecutionHeartbeatRegistry();
        var heartbeat = new com.specagent.runtime.execution.LeaseHeartbeat(worker, registry, shortLease, java.util.List.of());
        registry.register(claim.token());
        try {
            heartbeat.start();
            // 令租约立即过期:下一次心跳的条件续租必然落空。
            jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                    + "WHERE id = :id", Map.of("id", taskId));
            Thread.sleep(2000);
            assertThat(registry.consumeOwnershipLost(claim.token()))
                    .as("an expired lease must mark ownership lost and never be revived")
                    .isTrue();
        } finally {
            heartbeat.stop();
            registry.unregister(claim.token());
            jdbc.update("UPDATE runtime_tasks SET lease_until = NULL "
                    + "WHERE id = :id", Map.of("id", taskId));
        }
    }

    /**
     * 缺口三回归(受保护写入护栏):{@code TaskWriteGuard.lockOwnedTask} 取到
     * task 行锁后才核实租约/deadline,校验必须用实时数据库时钟——事务在租约
     * 有效时开始、等待行锁期间跨过到期点,取得锁后必须拒绝业务写入,不留下
     * 任何业务产物。同时验证既有协议:在租约仍然有效的窗口内正确取锁并通过
     * 验证的短事务可以正常完成(到期不等于强制回滚已授权事务)。
     */
    @Test
    void guardedWriteRefusedWhenLeaseExpiresDuringTaskRowLockWait() throws Exception {
        Project project = trackedProject("护栏跨期 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim claim = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();
        var guard = survivingGuard();
        var tx = new TransactionTemplate(txManager);

        // 协议正向:租约有效时,受保护短事务取锁、验证、提交业务写入。
        Integer healthy = tx.execute(t -> {
            guard.lockOwnedTask(claim.token());
            return jdbc.update("UPDATE agent_runs SET trace = '\"healthy\"' WHERE id = :id",
                    Map.of("id", claim.runId()));
        });
        assertThat(healthy).as("a verified short transaction must be able to complete").isEqualTo(1);

        // 反例:租约 1 秒后到期;护栏事务立即开始(其 now() 若被误用将早于
        // 到期点),在 FOR UPDATE 上等待,跨过到期点取得锁后必须被拒绝。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() + interval '1 second' "
                + "WHERE id = :id", Map.of("id", taskId));

        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(t -> {
                        jdbc.queryForList("SELECT id FROM runtime_tasks WHERE id=:id FOR UPDATE",
                                Map.of("id", taskId));
                        locked.countDown();
                        try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> {
                try {
                    tx.execute(t -> {
                        guard.lockOwnedTask(claim.token());
                        // 到了这里意味着护栏错误地放行了过期租约:写入一个
                        // 可检测的业务产物,断言阶段必须看不到它。
                        jdbc.update("UPDATE agent_runs SET trace = '\"late-write\"' WHERE id = :id",
                                Map.of("id", claim.runId()));
                        return 1;
                    });
                    return true;
                } catch (com.specagent.runtime.execution.TaskOwnershipLostException expected) {
                    return false;
                }
            });
            // 跨过 1 秒到期点之后再释放锁,保证护栏是在锁等待中跨期的。
            Thread.sleep(1200);
            release.countDown();
            boolean lateWriteCommitted = writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(lateWriteCommitted)
                    .as("a lease that expires while the guard waits for the task row lock "
                            + "must refuse the protected write")
                    .isFalse();
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
            // 无残留产物:事务整体回滚,业务行保持健康事务写入的值。
            String trace = jdbc.queryForObject("SELECT trace#>>'{}' FROM agent_runs WHERE id = :id",
                    Map.of("id", claim.runId()), String.class);
            assertThat(trace)
                    .as("a refused late write must leave no residue")
                    .isEqualTo("healthy");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 缺口三回归:续租事务在租约到期之前开始,却被行锁挡到到期之后才执行。
     * 用 {@code now()}(事务开始时间)的旧实现会把已过期的租约"复活";用实时
     * 数据库时钟核实则必须拒绝续租(返回 0)。
     */
    @Test
    void renewalAcrossLeaseExpiryDuringLockWaitIsRefusedNotRevived() throws Exception {
        Project project = trackedProject("续租跨期 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim claim = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();
        // 租约 1 秒后到期。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() + interval '1 second' "
                + "WHERE id = :id", Map.of("id", taskId));

        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(tx -> {
                        jdbc.queryForList("SELECT id FROM runtime_tasks WHERE id=:id FOR UPDATE",
                                Map.of("id", taskId));
                        locked.countDown();
                        try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 续租事务立即开始(now() 早于到期),随后被行锁挡住。
            var renewer = pool.submit(() -> new TransactionTemplate(txManager)
                    .execute(tx -> tasks.renewLease(claim.token(), 60)));
            // 跨过 1 秒到期点之后再释放锁。
            Thread.sleep(1200);
            release.countDown();
            Integer renewed = renewer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(renewed)
                    .as("a lease that expires while its renewal waits for the row lock must not be revived")
                    .isEqualTo(0);
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 缺口三回归:一个任务的续租被行锁阻塞时,其他任务的续租必须仍然及时完成
     * (各自独立事务,不共用连接/不排队等待被阻塞的行锁)。
     */
    @Test
    void aBlockedRenewalDoesNotDelayAnotherTasksRenewal() throws Exception {
        Project first = trackedProject("并发续租 A " + UUID.randomUUID());
        Project second = trackedProject("并发续租 B " + UUID.randomUUID());
        queuedTask(first.id(), "DECISION_CYCLE");
        queuedTask(second.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler workerA = schedulerFor(new WorkerIdentity());
        RuntimeTaskScheduler workerB = schedulerFor(new WorkerIdentity());
        ExecutionClaim blocked = workerA.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();
        ExecutionClaim other = workerB.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();

        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(tx -> {
                        jdbc.queryForList("SELECT id FROM runtime_tasks WHERE id=:id FOR UPDATE",
                                Map.of("id", blocked.taskId()));
                        locked.countDown();
                        try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            Integer renewed = new TransactionTemplate(txManager)
                    .execute(tx -> tasks.renewLease(other.token(), 60));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(renewed)
                    .as("another task's renewal must still succeed while one row is locked")
                    .isEqualTo(1);
            assertThat(elapsedMs)
                    .as("it must not wait on the blocked task's row lock")
                    .isLessThan(1000);
            release.countDown();
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 缺口三回归(真实心跳循环):一个任务的续租被行锁阻塞时,生产形态的
     * 单线程 {@code LeaseHeartbeat} 必须仍让其他任务的续租按时完成——证据是
     * B 的租约在 A 被阻塞的整个窗口内被心跳推进过至少一次,且 A、B 都没有被
     * 误判失权。
     *
     * <p>与直接调 repository 的版本不同,这里 A、B 都是注册到真实心跳执行器的
     * 真实任务:续租请求由心跳线程发起,先证实 A 的续租确实进入了锁等待
     * (A 的租约在阻塞期间一次也没有推进),再观察 B 在同一窗口内被续租。
     * 连接获取、锁等待与语句执行的时间边界由 renewLease 的
     * {@code lock_timeout=2s}/{@code statement_timeout=10s} 与 4s 短租约共同
     * 约束:受阻的 A 绝不能耗尽 B 的租约。
     */
    @Test
    void heartbeatLoopKeepsOtherTaskAliveWhileOneRenewalWaitsForARowLock() throws Exception {
        Project first = trackedProject("心跳受阻 A " + UUID.randomUUID());
        Project second = trackedProject("心跳存活 B " + UUID.randomUUID());
        UUID taskA = queuedTask(first.id(), "DECISION_CYCLE");
        UUID taskB = queuedTask(second.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler workerA = schedulerFor(new WorkerIdentity());
        RuntimeTaskScheduler workerB = schedulerFor(new WorkerIdentity());
        ExecutionClaim blocked = workerA.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();
        ExecutionClaim other = workerB.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();

        RuntimeExecutionSettings shortLease = new RuntimeExecutionSettings();
        shortLease.setLeaseSeconds(4);
        shortLease.setHeartbeatSeconds(1);
        var registry = new com.specagent.runtime.execution.ExecutionHeartbeatRegistry();
        var listener = new RecordingLostListener();
        var heartbeat = new com.specagent.runtime.execution.LeaseHeartbeat(
                workerA, registry, shortLease, List.of(listener));
        registry.register(blocked.token());
        registry.register(other.token());

        java.sql.Timestamp aLeaseAtBlock = jdbc.queryForObject(
                "SELECT lease_until FROM runtime_tasks WHERE id = :id",
                Map.of("id", taskA), java.sql.Timestamp.class);

        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            heartbeat.start();
            // 阻塞 A 的 task 行:B 的续租与该行锁无关,必须照常推进。
            var blocker = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(tx -> {
                        jdbc.queryForList("SELECT id FROM runtime_tasks WHERE id=:id FOR UPDATE",
                                Map.of("id", taskA));
                        locked.countDown();
                        try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // 给心跳循环留出至少两个完整周期去撞 A 的行锁并推进 B。
            Thread.sleep(2500);

            java.sql.Timestamp aLeaseBlocked = jdbc.queryForObject(
                    "SELECT lease_until FROM runtime_tasks WHERE id = :id",
                    Map.of("id", taskA), java.sql.Timestamp.class);
            java.sql.Timestamp bLease = jdbc.queryForObject(
                    "SELECT lease_until FROM runtime_tasks WHERE id = :id",
                    Map.of("id", taskB), java.sql.Timestamp.class);

            // A 的续租确实进入了锁等待:整个窗口内 A 的租约一次都没有被推进。
            assertThat(aLeaseBlocked)
                    .as("A's renewal must be stuck on the row lock, not silently skipped")
                    .isEqualTo(aLeaseAtBlock);
            // B 在同一窗口内被心跳续租(至少推进过一次,4s 租约 + 2.5s 窗口)。
            assertThat(bLease.toInstant())
                    .as("the heartbeat loop must keep renewing B while A waits for its row lock")
                    .isAfter(java.time.Instant.now());
            // 心跳周期边界:B 的租约仍然领先当前时刻一个心跳周期以上——一次
            // 受阻的续租(锁等待 + 语句执行的上界)不会耗尽其他任务的租约。
            assertThat(java.time.Duration.between(java.time.Instant.now(), bLease.toInstant()))
                    .as("B's remaining lease must comfortably exceed one heartbeat period")
                    .isGreaterThan(java.time.Duration.ofSeconds(2));
            assertThat(listener.lostTokens)
                    .as("a blocked renewal must never be misread as ownership loss")
                    .isEmpty();
            assertThat(registry.isOwnershipLost(blocked.token())).isFalse();
            assertThat(registry.isOwnershipLost(other.token())).isFalse();

            release.countDown();
            blocker.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            heartbeat.stop();
            registry.unregister(blocked.token());
            registry.unregister(other.token());
            pool.shutdownNow();
        }
    }

    /** 记录失权回调的监听器(失权传播契约在该测试族中的探针)。 */
    private static final class RecordingLostListener
            implements com.specagent.runtime.execution.LeaseHeartbeat.OwnershipLostListener {
        final java.util.List<com.specagent.runtime.execution.ExecutionToken> lostTokens =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        @Override
        public void onOwnershipLost(com.specagent.runtime.execution.ExecutionToken token) {
            lostTokens.add(token);
        }
    }

    /**
     * 缺口二回归:心跳失权信号必须被真实执行链消费——本进程已判定失权时,
     * <b>即使数据库租约仍然有效</b>,受保护写入也必须立即拒绝(不得继续发起步骤)。
     * 数据库护栏仍是最终权威,但内存信号负责"已知失权即刻停手"。
     */
    @Test
    void knownLostOwnershipRefusesProtectedWritesEvenWithValidLease() {
        Project project = trackedProject("失权信号 " + UUID.randomUUID());
        UUID taskId = queuedTask(project.id(), "DECISION_CYCLE");
        RuntimeTaskScheduler worker = schedulerFor(new WorkerIdentity());
        ExecutionClaim claim = worker.tryClaim(
                List.of(RuntimeTaskFamily.WORKSPACE), schedulingOnlyPorts(), resolvers()).orElseThrow();
        assertThat(tasks.findById(taskId).orElseThrow().state())
                .as("the database lease is still valid at this point")
                .isEqualTo(RuntimeTaskState.LEASED);

        var registry = new com.specagent.runtime.execution.ExecutionHeartbeatRegistry();
        registry.register(claim.token());
        var guard = new com.specagent.runtime.execution.TaskWriteGuard(jdbc, taskAuthorization);
        guard.setHeartbeatRegistry(registry);
        var tx = new TransactionTemplate(txManager);

        Boolean ownedBefore = tx.execute(t -> guard.stillOwned(claim.token()));
        assertThat(ownedBefore)
                .as("before any loss the guard must accept the still-valid lease")
                .isTrue();
        registry.markOwnershipLost(claim.token());
        Boolean ownedAfter = tx.execute(t -> guard.stillOwned(claim.token()));
        assertThat(ownedAfter)
                .as("a known-lost execution must refuse protected writes even with a valid lease")
                .isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tx.execute(t -> guard.lockOwnedTask(claim.token())))
                .isInstanceOf(com.specagent.runtime.execution.TaskOwnershipLostException.class);
        registry.unregister(claim.token());
    }

    /**
     * 缺口二回归:失权标记与登记一律按完整 token 绑定——旧 attempt 的心跳结果或
     * finally 清理绝不污染、也不摘掉新 attempt 的登记与标记。
     */
    @Test
    void ownershipLostSignalIsScopedToTheAttemptToken() {
        var registry = new com.specagent.runtime.execution.ExecutionHeartbeatRegistry();
        UUID taskId = UUID.randomUUID();
        com.specagent.runtime.execution.ExecutionToken first = tokenFor(taskId, 1L);
        com.specagent.runtime.execution.ExecutionToken second = tokenFor(taskId, 2L);

        registry.register(first);
        registry.markOwnershipLost(first);
        assertThat(registry.isOwnershipLost(first)).isTrue();
        assertThat(registry.isOwnershipLost(second))
                .as("an old attempt's loss marker must not affect the new attempt")
                .isFalse();

        registry.register(second);
        registry.unregister(first);
        assertThat(registry.isOwnershipLost(first)).isFalse();
        assertThat(registry.inFlightTokens())
                .as("an old attempt's cleanup must not remove the new attempt's registration")
                .contains(second);
        registry.unregister(second);
    }

    private static com.specagent.runtime.execution.ExecutionToken tokenFor(UUID taskId, long epoch) {
        return new com.specagent.runtime.execution.ExecutionToken(taskId, epoch,
                UUID.randomUUID(), UUID.randomUUID(), RuntimeTaskFamily.WORKSPACE, UUID.randomUUID());
    }

    private com.specagent.runtime.execution.TaskWriteGuard survivingGuard() {
        return new com.specagent.runtime.execution.TaskWriteGuard(jdbc,taskAuthorization);
    }
}
