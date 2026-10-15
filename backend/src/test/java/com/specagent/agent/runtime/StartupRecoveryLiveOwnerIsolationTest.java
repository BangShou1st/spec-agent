package com.specagent.agent.runtime;

import com.specagent.common.Ids;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import com.specagent.runtime.execution.WorkerIdentity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:StartupRecoveryLiveOwnerIsolationTest.java
 *
 * 测试目标:design §12 的验收项"新 Worker 启动:不终止其他 Worker 活跃任务;
 * 排队任务不丢"。
 *
 * <p>旧的启动孤儿恢复依赖"同一时刻至多一个执行器进程"这一边界,会把数据库里
 * 任何非终态 Run 都当作已死执行器的遗留物。多 Worker 下那是**错误**的:另一个
 * 实例正在执行的 Run 也是非终态,却绝不能被本进程的启动恢复终止。
 *
 * <p>本测试用真实调度事实证明:持有存活租约的 Run 被跳过,只有无存活 owner 的
 * Run 才被收敛;排队任务不受影响。
 */
@SpringBootTest
@ActiveProfiles("test")
class StartupRecoveryLiveOwnerIsolationTest {

    @Autowired AgentRunOrphanRecoveryService recovery;
    @Autowired RuntimeTaskRepository tasks;
    @Autowired RuntimeExecutionSettings settings;
    @Autowired RuntimeTaskScheduler scheduler;
    @Autowired ProjectService projects;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired List<TaskLifecyclePort> lifecyclePorts;
    @Autowired List<TaskScopeResolver> scopeResolvers;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
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
        tasks.reconcileCapacity();
    }

    private Project trackedProject(String title) {
        Project project = projects.createProject(title);
        touchedProjects.add(project.id());
        return project;
    }

    private UUID queuedRun(UUID projectId, String status) {
        UUID runId = Ids.random();
        jdbc.update("""
                INSERT INTO agent_runs (id, project_id, trigger_type, status, trace,
                    created_at, owner_epoch)
                VALUES (:id, :projectId, 'DECISION_CYCLE', :status, NULL, now(), 0)
                """, Map.of("id", runId, "projectId", projectId, "status", status));
        return runId;
    }

    private String statusOf(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = :id",
                Map.of("id", runId), String.class);
    }

    @Test
    void startupRecoveryNeverTerminalizesRunsLeasedByAnotherLiveWorker() {
        Project project = trackedProject("存活租约隔离 " + UUID.randomUUID());
        // 属于另一个存活 Worker 的 Run:先以 created 建行,再由那个 Worker 认领
        // (认领事务会把它推进为 RUNNING 并持有存活租约——这正是要保护的状态)。
        UUID liveRunId = queuedRun(project.id(), "created");
        // 真正的孤儿:非终态但没有任何存活 owner。
        UUID orphanRunId = queuedRun(project.id(), "model_called");
        // 排队任务:绝不是孤儿。
        UUID queuedId = queuedRun(project.id(), "created");

        // 为 liveRun 建一个 QUEUED 任务并由"另一个 Worker"认领(存活租约)。
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, 'DECISION_CYCLE',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", Ids.random(), "runId", liveRunId));
        UUID liveTaskId = jdbc.queryForObject(
                "SELECT id FROM runtime_tasks WHERE workspace_run_id = :runId",
                Map.of("runId", liveRunId), UUID.class);
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (:id, :scope)",
                Map.of("id", liveTaskId, "scope", "project:" + project.id()));

        RuntimeTaskScheduler otherWorker = new RuntimeTaskScheduler(tasks, settings,
                new WorkerIdentity(), new TransactionTemplate(txManager));
        ExecutionClaim claim = otherWorker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
                lifecyclePorts, scopeResolvers).orElseThrow();
        assertThat(claim.runId()).isEqualTo(liveRunId);

        // 新进程启动并执行孤儿恢复。
        recovery.recoverOrphans();

        // 存活租约的 Run 绝不被终止。
        assertThat(statusOf(liveRunId))
                .as("a run leased by another live worker must never be terminalized "
                        + "by this process's startup recovery")
                .isEqualTo("running");
        // 真正的孤儿被诚实终态化。
        assertThat(statusOf(orphanRunId)).isEqualTo("failed");
        // 排队任务不受影响,仍在等待认领。
        assertThat(statusOf(queuedId)).isEqualTo("created");
    }

    @Test
    void expiredLeaseMakesTheRunAnOrphanAgain() {
        Project project = trackedProject("租约过期即孤儿 " + UUID.randomUUID());
        UUID runId = queuedRun(project.id(), "created");

        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', :id, 'WORKSPACE', :runId, NULL, 'DECISION_CYCLE',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, Map.of("id", Ids.random(), "runId", runId));
        UUID taskId = jdbc.queryForObject(
                "SELECT id FROM runtime_tasks WHERE workspace_run_id = :runId",
                Map.of("runId", runId), UUID.class);
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (:id, :scope)",
                Map.of("id", taskId, "scope", "project:" + project.id()));

        RuntimeTaskScheduler otherWorker = new RuntimeTaskScheduler(tasks, settings,
                new WorkerIdentity(), new TransactionTemplate(txManager));
        otherWorker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
                lifecyclePorts, scopeResolvers).orElseThrow();

        // 租约过期(等价于那个 Worker 死掉):该 Run 重新成为孤儿。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", taskId));

        recovery.recoverOrphans();

        assertThat(statusOf(runId))
                .as("once the lease expires the run is a genuine orphan")
                .isEqualTo("failed");
    }
}
