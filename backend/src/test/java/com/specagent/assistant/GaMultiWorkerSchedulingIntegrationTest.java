package com.specagent.assistant;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:GaMultiWorkerSchedulingIntegrationTest.java
 *
 * 测试目标:全局助手在公共调度层下的并行边界与诚实失败(design §4.2、§6、§7、§12)。
 *
 * <p>验证:
 * <ol>
 *   <li><b>不同会话并行</b>:两个会话各自的任务可被同时认领;</li>
 *   <li><b>同一会话串行</b>:同一 thread 的第二个任务被范围挡住,但不影响其他会话;</li>
 *   <li><b>入队持久化执行输入</b>:message 与 UI context 落库,崩溃后仍可重建;</li>
 *   <li><b>按租约诚实终态化</b>:未注册恢复适配器时,租约过期 → 任务失败 +
 *       {@code EXECUTION_OUTCOME_UNKNOWN},且业务 Run 一并收敛为失败——
 *       绝不留一个永不推进的"进行中"会话。</li>
 * </ol>
 *
 * <p>用真实 PostgreSQL 与独立构造的调度器实例(等价于两个 Worker 进程)。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spec.agent.execution.multi-worker-enabled=true",
        // 缓存的单执行器测试上下文持有排他 advisory lock,与任务级上下文的
        // 共享锁在同一 JVM 内冲突;生产部署不得关闭准入。
        "spec.agent.execution.mode-admission-enabled=false",
})
class GaMultiWorkerSchedulingIntegrationTest {

    @Autowired RuntimeTaskRepository tasks;
    @Autowired RuntimeExecutionSettings settings;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired List<TaskLifecyclePort> lifecyclePorts;
    @Autowired List<TaskScopeResolver> scopeResolvers;

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
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t)",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = :t", Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_threads WHERE id = :t", Map.of("t", threadId));
        }
        touchedThreads.clear();
        tasks.reconcileCapacity();
    }

    private RuntimeTaskScheduler worker() {
        return new RuntimeTaskScheduler(tasks,
                new RuntimeExecutionSettings(), new WorkerIdentity(),
                new TransactionTemplate(txManager));
    }

    /** 建一个会话并直接落一行 GA Run + 调度任务(绕过模型配置依赖)。 */
    private record GaFixture(UUID threadId, UUID runId, UUID taskId) {
    }

    private GaFixture gaFixture() {
        UUID threadId = UUID.randomUUID();
        touchedThreads.add(threadId);
        jdbc.update("INSERT INTO global_assistant_threads (id, org_id, owner_user_id, created_at, updated_at) "
                + "VALUES (:id, '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', now(), now())", Map.of("id", threadId));
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO global_assistant_runs (id, thread_id, status, step_count, started_at,
                    prompt_version, context_projection_version, tool_catalog_fingerprint, engine_version)
                VALUES (:id, :threadId, 'CREATED', 0, now(), 'langchain-ga.v1', 'ga-host.v1', 'v1',
                    'langchain-ga.v1')
                """, Map.of("id", runId, "threadId", threadId));
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'GLOBAL_ASSISTANT', NULL, :runId, 'ga_execution',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", taskId, "runId", runId));
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (:id, :scope)",
                Map.of("id", taskId, "scope", "ga-thread:" + threadId));
        return new GaFixture(threadId, runId, taskId);
    }

    @Test
    void differentThreadsClaimInParallelButOneThreadStaysSerial() {
        GaFixture first = gaFixture();
        GaFixture second = gaFixture();

        RuntimeTaskScheduler workerA = worker();
        RuntimeTaskScheduler workerB = worker();

        // 两个不同会话的任务可以同时被认领(不同范围)。
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();
        ExecutionClaim claimB = workerB.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();
        assertThat(java.util.Set.of(claimA.taskId(), claimB.taskId()))
                .as("different threads must be able to run in parallel")
                .hasSize(2);
        assertThat(tasks.scopesOf(claimA.taskId()))
                .isNotEqualTo(tasks.scopesOf(claimB.taskId()));

        // 同一会话的第二个任务必须被范围挡住(保留"至多一个活跃 Run"语义)。
        // 唯一活跃索引只允许同会话一个活跃 Run,因此第二个 Run 先建成终态再建任务
        // ——这里要验证的是<b>范围锁</b>,而不是活跃唯一性(后者由数据库索引保证)。
        UUID extraRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO global_assistant_runs (id, thread_id, status, step_count, started_at,
                    prompt_version, context_projection_version, tool_catalog_fingerprint, engine_version)
                VALUES (:id, :threadId, 'COMPLETED', 0, now(), 'langchain-ga.v1', 'ga-host.v1', 'v1',
                    'langchain-ga.v1')
                """, Map.of("id", extraRunId, "threadId", first.threadId()));
        UUID extraTaskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'GLOBAL_ASSISTANT', NULL, :runId, 'ga_execution',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", extraTaskId, "runId", extraRunId));
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (:id, :scope)",
                Map.of("id", extraTaskId, "scope", "ga-thread:" + first.threadId()));

        RuntimeTaskScheduler workerC = worker();
        var blocked = workerC.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers);
        assertThat(blocked)
                .as("the same thread must stay serial while its scope is held")
                .isEmpty();
    }

    @Test
    void persistedInputSurvivesSoATakeoverCanReconstructTheExecution() {
        GaFixture fixture = gaFixture();
        // 模拟入队时持久化执行输入(生产由 GaTaskEnqueuer 在同一事务内完成)。
        jdbc.update("UPDATE runtime_tasks SET input_payload = "
                        + "CAST('{\"message\":\"请说明部署步骤\",\"currentPage\":\"PROJECT\"}' AS jsonb) "
                        + "WHERE id = :id", Map.of("id", fixture.taskId()));

        var inputs = new com.specagent.runtime.execution.RuntimeTaskInputStore(jdbc);
        assertThat(inputs.read(fixture.taskId()))
                .as("dispatch parameters must be durable, not only in memory")
                .isPresent()
                .get().asString().contains("请说明部署步骤");
    }

    @Test
    void expiredLeaseWithoutRecoveryAdapterFailsHonestlyAndConvergesTheRun() {
        GaFixture fixture = gaFixture();

        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claim = workerA.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                lifecyclePorts, scopeResolvers).orElseThrow();

        // 进程死亡:租约过期。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claim.taskId()));

        RuntimeTaskScheduler survivor = worker();
        int recovered = survivor.recoverExpiredLeases(lifecyclePorts, List.of());
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code FROM runtime_tasks WHERE id = :id",
                Map.of("id", claim.taskId()));
        assertThat(taskRow.get("state"))
                .as("GA recovery is the second step and is off by default; fail honestly")
                .isEqualTo("FAILED");
        assertThat(taskRow.get("last_error_code")).isEqualTo("EXECUTION_OUTCOME_UNKNOWN");

        // 业务 Run 必须一并收敛:绝不留一个永不推进的"进行中"会话。
        assertThat(jdbc.queryForObject(
                "SELECT status FROM global_assistant_runs WHERE id = :id",
                Map.of("id", fixture.runId()), String.class))
                .as("an expired GA run must not stay active forever")
                .isIn("FAILED", "CANCELLED");

        // 容量与范围都已释放。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM runtime_scope_owners WHERE task_id = :id",
                Map.of("id", claim.taskId()), Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT used FROM runtime_capacity_slots WHERE slot_key = 'GLOBAL'",
                Map.of(), Integer.class)).isZero();
    }
}
