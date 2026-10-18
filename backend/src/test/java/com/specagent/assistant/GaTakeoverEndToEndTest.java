package com.specagent.assistant;

import com.specagent.assistant.runtime.GaExecutionRecoveryAdapter;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RecoveryAdapter;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import com.specagent.runtime.execution.TaskWriteGuard;
import com.specagent.runtime.execution.WorkerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:GaTakeoverEndToEndTest.java
 *
 * 测试目标:GA 接管的端到端故障注入(design §7 第二步、§12)——对等画布侧的
 * {@code AnswerChainTakeoverEndToEndTest},验证"模型/工具调用已记账 → kill →
 * 接管 → 预算与账本一致"的完整链路。
 *
 * <p>GA 自动恢复({@code ga-recovery-enabled=true})只在<b>本测试类</b>内开启;
 * 生产默认保持关闭,直到本测试与真实双进程验收(design §12)同时通过。
 *
 * <p>与画布侧的差异:GA 的业务执行需要真实 Python 进程,因此"接管后真的继续"
 * 的执行部分由生产路径({@code GaExecutionStore.initialize} 的接管分支)承载:
 * 新 Worker 认领后按新代次/新租约重新绑定执行——这正是真实接管执行的第一步。
 * 账本与预算的断言覆盖 design §7 的核心要求:
 * <ol>
 *   <li>存在 RESERVED/UNKNOWN 调用时<b>拒绝接管</b>(外部副作用可能已发生);</li>
 *   <li>安全接管时<b>预算绝不重置</b>(model_calls/tool_calls 原样保留),
 *       {@code executor_claimed} 重置让新 attempt 重新做"恰好一次"CAS;</li>
 *   <li>旧 Worker 的 token 在接管后<b>不能提交任何东西</b>;</li>
 *   <li>没有 checkpoint head 时<b>拒绝接管</b>(没有可沿用的续跑点)。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spec.agent.execution.multi-worker-enabled=true",
        "spec.agent.execution.mode-admission-enabled=false",
        "spec.agent.execution.ga-recovery-enabled=true",
})
class GaTakeoverEndToEndTest {
    @Autowired com.specagent.runtime.execution.RuntimeTaskAuthorization taskAuthorization;
    @Autowired com.specagent.assistant.conversation.GlobalAssistantConversationService conversations;

    @Autowired RuntimeTaskRepository tasks;
    @Autowired RuntimeExecutionSettings settings;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired List<TaskLifecyclePort> lifecyclePorts;
    @Autowired List<TaskScopeResolver> scopeResolvers;
    @Autowired GaExecutionRecoveryAdapter recoveryAdapter;
    @Autowired GaExecutionStore executions;

    private final List<UUID> touchedThreads = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID threadId : touchedThreads) {
            jdbc.update("DELETE FROM runtime_task_attempts WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE ga_run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t))",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE ga_run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t))",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE ga_run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t))",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE ga_run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t))",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM runtime_tasks WHERE ga_run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t)",
                    Map.of("t", threadId));
            // ga_execution_calls / ga_executions 随 runs 级联;ga_checkpoint_*
            // 随 threads 级联。
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t)",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_threads WHERE id = :t",
                    Map.of("t", threadId));
        }
        touchedThreads.clear();
        tasks.reconcileCapacity();
    }

    private RuntimeTaskScheduler worker() {
        return new RuntimeTaskScheduler(tasks, new RuntimeExecutionSettings(),
                new WorkerIdentity(), new TransactionTemplate(txManager));
    }

    /**
     * 落一个"Worker A 已认领并完成执行绑定、一次模型调用已记账"的完整现场:
     * 会话 + Run(CREATED, langchain 引擎)+ 调度任务 + 执行绑定(代次 1、
     * A 的租约、executor_claimed=TRUE)+ 预算计数(model_calls=2, tool_calls=1)
     * + 一条已完成的调用账本行 + checkpoint head。
     */
    private record GaFixture(UUID threadId, UUID runId, UUID taskId, UUID leaseA) {
    }

    private GaFixture crashedAfterLedgeredModelCall(String model) {
        UUID threadId = conversations.createThread().id();
        touchedThreads.add(threadId);
        UUID runId = conversations.createRun(threadId,"langchain-ga.v1","ga-host.v1","v1").id();
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'GLOBAL_ASSISTANT', NULL, :runId, 'ga_execution',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", taskId, "runId", runId));
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (:id, :scope)",
                Map.of("id", taskId, "scope", "ga-thread:" + threadId));

        // Worker A 的执行绑定:真实生产形态由 GaExecutionStore.initialize 写出;
        // 这里等价落库(接管者的 initialize 更新同一行,断言才有意义)。
        UUID leaseA = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ga_executions (run_id, thread_id, model_binding_id, provider, model,
                    settings_revision, execution_epoch, lease_id, deadline,
                    model_calls, tool_calls, allowed_tools, capability_catalog, catalog_hash,
                    executor_claimed)
                VALUES (:run, :thread, :binding, 'OPENCODE_ZEN', :model, 'test-revision',
                    1, :leaseA, now() + interval '120 seconds', 2, 1, '[]', '[]', :hash, TRUE)
                """, Map.of("run", runId, "thread", threadId, "binding", UUID.randomUUID(),
                "model", model, "leaseA", leaseA, "hash", "a".repeat(64)));

        // 一次已完成的模型调用(有回执):接管时账本必须原样保留。
        jdbc.update("""
                INSERT INTO ga_execution_calls (run_id, call_id, kind, payload_hash, status, result)
                VALUES (:run, :call, 'MODEL', :hash, 'SUCCEEDED', :result)
                """, Map.of("run", runId, "call", UUID.randomUUID(),
                "hash", "b".repeat(64), "result", "{\"ok\":true}"));

        // checkpoint head 必须存在(接管核验的续跑点前提)。
        jdbc.update("INSERT INTO ga_checkpoint_heads (thread_id) VALUES (:thread) "
                + "ON CONFLICT DO NOTHING", Map.of("thread", threadId));
        return new GaFixture(threadId, runId, taskId, leaseA);
    }

    @Test
    void cancellationScopeUsesBindingLeaseAndRejectsStaleEpoch() {
        GaFixture fixture = crashedAfterLedgeredModelCall("cancel-binding-scope");
        var scope = executions.cancellationScope(fixture.runId(), 1).orElseThrow();
        assertThat(scope.leaseId()).isEqualTo(fixture.leaseA());
        UUID successorLease = UUID.randomUUID();
        jdbc.update("UPDATE ga_executions SET execution_epoch=2,lease_id=:lease WHERE run_id=:run",
                Map.of("lease", successorLease, "run", fixture.runId()));
        assertThat(executions.cancellationScope(fixture.runId(), 1)).isEmpty();
        assertThat(executions.cancellationScope(fixture.runId(), 2).orElseThrow().leaseId()).isEqualTo(successorLease);
    }

    @Test
    void ledgeredModelCallSurvivesTakeoverWithoutResettingBudget() {
        GaFixture fixture = crashedAfterLedgeredModelCall("takeover-safe-model");

        // ── Worker A 认领,然后进程死亡(租约过期,不再心跳)──
        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();
        assertThat(claimA.runId()).isEqualTo(fixture.runId());
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claimA.taskId()));

        // ── 存活实例做过期恢复:真实 GA 适配器核验 ──
        RuntimeTaskScheduler survivor = worker();
        int recovered = survivor.recoverExpiredLeases(lifecyclePorts, List.of(recoveryAdapter));
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code FROM runtime_tasks WHERE id = :id",
                Map.of("id", claimA.taskId()));
        assertThat(taskRow.get("state"))
                .as("a ledgered (fully receipted) call has no unknown side effect; takeover is safe")
                .isEqualTo("QUEUED");
        assertThat(taskRow.get("last_error_code")).isNull();

        // ── 旧 Worker 恢复后不能提交任何东西 ──
        var guard = new TaskWriteGuard(jdbc,taskAuthorization);
        assertThat(guard.stillOwned(claimA.token()))
                .as("the dead worker's token must be invalid after takeover")
                .isFalse();

        // ── 新 Worker 接管:认领 + 按新代次重新绑定执行(生产接管第一步)──
        RuntimeTaskScheduler workerB = worker();
        ExecutionClaim claimB = claimEventually(workerB, fixture.runId());
        assertThat(claimB.token().epoch())
                .as("takeover must strictly increase the execution epoch")
                .isGreaterThan(claimA.token().epoch());

        // 生产路径:接管执行从 GaExecutionStore.initialize 按新代次/新租约开始。
        UUID originalBinding = jdbc.queryForObject(
                "SELECT model_binding_id FROM ga_executions WHERE run_id = :run",
                Map.of("run", fixture.runId()), UUID.class);
        // 接管绑定<b>故意</b>使用不同的提供商与模型:冻结快照绝不能被"再绑定"改写。
        executions.initialize(new GaExecutionStore.Scope(fixture.runId(), claimB.token().epoch(),
                        claimB.token().leaseId()),
                new GaExecutionStore.Binding(fixture.threadId(), UUID.randomUUID(),
                        "OPENROUTER", "rebind-attempt", "test-revision",
                        Instant.now().plusSeconds(120), List.of()));

        // ── 核心断言:预算与账本一致(design §7)──
        Map<String, Object> binding = jdbc.queryForMap("""
                SELECT execution_epoch, lease_id, executor_claimed, model_calls, tool_calls,
                       provider, model, model_binding_id
                FROM ga_executions WHERE run_id = :run
                """, Map.of("run", fixture.runId()));
        assertThat(((Number) binding.get("execution_epoch")).longValue())
                .as("the binding must be rebound to the new attempt's epoch")
                .isEqualTo(claimB.token().epoch());
        assertThat(binding.get("lease_id"))
                .as("the binding must carry the new lease")
                .isEqualTo(claimB.token().leaseId());
        assertThat(binding.get("executor_claimed"))
                .as("executor_claimed must be reset so the new attempt can redo the once-CAS")
                .isEqualTo(false);
        assertThat(((Number) binding.get("model_calls")).intValue())
                .as("model call budget must NEVER be reset on takeover (design §7)")
                .isEqualTo(2);
        assertThat(((Number) binding.get("tool_calls")).intValue())
                .as("tool call budget must NEVER be reset on takeover (design §7)")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ga_execution_calls WHERE run_id = :run "
                        + "AND status = 'SUCCEEDED'",
                Map.of("run", fixture.runId()), Integer.class))
                .as("the receipted ledger row must survive untouched")
                .isEqualTo(1);
        assertThat(binding.get("provider"))
                .as("the frozen provider must never be rebound on takeover")
                .isEqualTo("OPENCODE_ZEN");
        assertThat(binding.get("model"))
                .as("the frozen model must never be rebound on takeover")
                .isEqualTo("takeover-safe-model");
        assertThat(binding.get("model_binding_id"))
                .as("the frozen binding identity must survive takeover")
                .isEqualTo(originalBinding);
    }

    @Test
    void reservedCallStopsTakeoverAndConvergesTheRunHonestly() {
        GaFixture fixture = crashedAfterLedgeredModelCall("takeover-unsafe-model");
        // 注入故障:一条 RESERVED 调用(外部副作用可能已发生且没有回执)——
        // 这是 design §7 要求"默认不重放"的形态。
        jdbc.update("""
                INSERT INTO ga_execution_calls (run_id, call_id, kind, payload_hash, status)
                VALUES (:run, :call, 'TOOL', :hash, 'RESERVED')
                """, Map.of("run", fixture.runId(), "call", UUID.randomUUID(),
                "hash", "c".repeat(64)));

        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claimA.taskId()));

        RuntimeTaskScheduler survivor = worker();
        survivor.recoverExpiredLeases(lifecyclePorts, List.of(recoveryAdapter));

        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code, recovery_reason FROM runtime_tasks WHERE id = :id",
                Map.of("id", claimA.taskId()));
        assertThat(taskRow.get("state"))
                .as("a RESERVED call must stop automatic takeover")
                .isEqualTo("FAILED");
        assertThat(taskRow.get("last_error_code")).isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
        assertThat(String.valueOf(taskRow.get("recovery_reason")))
                .as("the operator must get a reconciliation record naming the unresolved call")
                .contains("RESERVED");

        // 业务 Run 一并收敛:不留一个永不推进的"进行中"会话
        // (uq_ga_runs_single_active 的唯一活跃槽必须释放)。
        assertThat(jdbc.queryForObject(
                "SELECT status FROM global_assistant_runs WHERE id = :id",
                Map.of("id", fixture.runId()), String.class))
                .isIn("FAILED", "CANCELLED");

        // 预算与账本未被自动清理(design §7"不得把旧 RESERVED 自动删除"):
        // 人工核对记录保留全部事实。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ga_execution_calls WHERE run_id = :run AND status = 'RESERVED'",
                Map.of("run", fixture.runId()), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT model_calls FROM ga_executions WHERE run_id = :run",
                Map.of("run", fixture.runId()), Integer.class)).isEqualTo(2);

        // 容量与范围都已释放。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM runtime_scope_owners WHERE task_id = :id",
                Map.of("id", claimA.taskId()), Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT used FROM runtime_capacity_slots WHERE slot_key = 'GLOBAL'",
                Map.of(), Integer.class)).isZero();
    }

    @Test
    void missingCheckpointHeadStopsTakeover() {
        GaFixture fixture = crashedAfterLedgeredModelCall("takeover-no-checkpoint");
        // 摘掉续跑点:没有 checkpoint head 就没有可沿用的快照。
        jdbc.update("DELETE FROM ga_checkpoint_heads WHERE thread_id = :t",
                Map.of("t", fixture.threadId()));

        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claimA.taskId()));

        RuntimeTaskScheduler survivor = worker();
        survivor.recoverExpiredLeases(lifecyclePorts, List.of(recoveryAdapter));

        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code, recovery_reason FROM runtime_tasks WHERE id = :id",
                Map.of("id", claimA.taskId()));
        assertThat(taskRow.get("state")).isEqualTo("FAILED");
        assertThat(taskRow.get("last_error_code")).isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
        assertThat(String.valueOf(taskRow.get("recovery_reason")))
                .as("the reconciliation record must name the missing resume point")
                .contains("checkpoint");
    }

    private ExecutionClaim claimEventually(RuntimeTaskScheduler worker, UUID expectedRunId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var claim = worker.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                    lifecyclePorts, scopeResolvers);
            if (claim.isPresent()) {
                assertThat(claim.get().runId()).isEqualTo(expectedRunId);
                return claim.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting claim", interrupted);
            }
        }
        throw new IllegalStateException(
                "task for GA run " + expectedRunId + " never became claimable again");
    }
}
