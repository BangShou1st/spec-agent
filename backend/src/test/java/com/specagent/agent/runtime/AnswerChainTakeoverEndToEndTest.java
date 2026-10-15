package com.specagent.agent.runtime;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RecoveryAdapter;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import com.specagent.runtime.execution.WorkerIdentity;
import com.specagent.runtime.execution.WorkspaceRunStepRepository;
import com.specagent.runtime.execution.WorkspaceRunStepRetryClass;
import com.specagent.runtime.execution.WorkspaceRunStepState;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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
 * 文件名:AnswerChainTakeoverEndToEndTest.java
 *
 * 测试目标:<b>第一阶段最关键验收的端到端证明</b>(design §12 / §13)——
 *
 * <blockquote>"认领后 / Answer 后 / Patch 后 kill 进程 → 租约到期接管;
 * 不重复 Answer/Patch;从核验 checkpoint 继续。"</blockquote>
 *
 * <p>本测试在<b>完整的任务级调度路径</b>上运行生产代码:入队 → 公共调度层认领
 * (容量槽 + 项目范围锁)→ 真实 {@code AnswerCycleService} 周期 → 过期恢复
 * (真实 {@code WorkspaceAnswerChainRecoveryAdapter})→ 新 Worker 接管 → 周期继续。
 *
 * <p>故障点精确落在 design §7 表格里最难的一行:Answer 已经不可变落库,但
 * STATE_UPDATE 的 checkpoint 尚未写出。这正是"接管是否会创建第二个 Answer"
 * 的判定场景。
 *
 * <p>断言:
 * <ol>
 *   <li>接管核验判定为<b>安全</b>(Answer 可验证、无未知副作用);</li>
 *   <li>接管后该节点仍然<b>只有一条</b> Answer,且就是原来那条(身份不变);</li>
 *   <li>周期从核验过的 checkpoint <b>继续</b>:补丁被创建,Run 到达终态;</li>
 *   <li>旧 Worker 的 token 在接管后<b>不能提交任何东西</b>。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spec.agent.execution.multi-worker-enabled=true",
        // 缓存的单执行器测试上下文持有排他 advisory lock,与任务级上下文的
        // 共享锁在同一 JVM 内冲突;生产部署不得关闭准入。
        "spec.agent.execution.mode-admission-enabled=false",
        "spec.agent.execution.lease-seconds=60",
})
class AnswerChainTakeoverEndToEndTest {
    @Autowired com.specagent.runtime.execution.RuntimeTaskAuthorization taskAuthorization;

    @Autowired RuntimeTaskScheduler scheduler;
    @Autowired RuntimeTaskRepository tasks;
    @Autowired WorkspaceRunStepRepository steps;
    @Autowired WorkspaceAnswerChainRecoveryAdapter recoveryAdapter;
    @Autowired WorkspaceTaskExecutionAdapter executionAdapter;
    @Autowired RunService runService;
    @Autowired RunWorker runWorker;
    @Autowired ProjectService projects;
    @Autowired NodeService nodes;
    @Autowired AnswerService answers;
    @Autowired List<TaskLifecyclePort> lifecyclePorts;
    @Autowired List<TaskScopeResolver> scopeResolvers;
    @Autowired List<RecoveryAdapter> recoveryAdapters;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final List<UUID> touchedProjects = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID projectId : touchedProjects) {
            jdbc.update("DELETE FROM workspace_run_steps WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_attempts WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id IN "
                    + "(SELECT id FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p))", Map.of("p", projectId));
            jdbc.update("DELETE FROM runtime_tasks WHERE workspace_run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("UPDATE agent_runs SET produced_answer_id = NULL, produced_patch_id = NULL, "
                    + "input_node_id = NULL, route_id = NULL WHERE project_id = :p",
                    Map.of("p", projectId));
            jdbc.update("UPDATE projects SET active_route_id = NULL WHERE id = :p",
                    Map.of("p", projectId));
            jdbc.update("UPDATE routes SET root_node_id = NULL, tip_node_id = NULL, "
                    + "created_from_node_id = NULL WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM answer_patches WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM answers WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("UPDATE agent_runs SET context_snapshot_id = NULL WHERE project_id = :p",
                    Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_events WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_run_continuation_checks WHERE run_id IN "
                    + "(SELECT id FROM agent_runs WHERE project_id = :p)", Map.of("p", projectId));
            jdbc.update("DELETE FROM agent_runs WHERE project_id = :p", Map.of("p", projectId));
            // context_snapshots / spec_snapshots / node_relations 都引用节点,
            // 必须在删节点之前清理(它们是本次真实周期执行产生的副作用);
            // agent_input_projections 随 context_snapshots 级联删除。
            jdbc.update("DELETE FROM spec_snapshots WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM context_snapshots WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM retrieval_source_projections WHERE project_id = :p",
                    Map.of("p", projectId));
            jdbc.update("DELETE FROM node_relations WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM nodes WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM retrieval_entries WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM routes WHERE project_id = :p", Map.of("p", projectId));
            // 触发本次周期的真实业务事实:proposal / capability 调用 / 图操作历史。
            jdbc.update("DELETE FROM capability_invocations WHERE project_id = :p",
                    Map.of("p", projectId));
            jdbc.update("DELETE FROM graph_operations WHERE project_id = :p", Map.of("p", projectId));
            jdbc.update("DELETE FROM projects WHERE id = :p", Map.of("p", projectId));
        }
        touchedProjects.clear();
        tasks.reconcileCapacity();
    }

    private RuntimeTaskScheduler worker() {
        return new RuntimeTaskScheduler(tasks,
                new com.specagent.runtime.execution.RuntimeExecutionSettings(),
                new WorkerIdentity(), new TransactionTemplate(txManager));
    }

    @Test
    void killedAfterAnswerIsPersistedIsTakenOverWithoutDuplicatingTheAnswer() {
        // ── 1. 用户提交回答:入队 + 公共调度层登记任务 ──
        Project project = projects.createProject("接管端到端 " + UUID.randomUUID());
        touchedProjects.add(project.id());
        Node root = nodes.createRootNode(project.id(), project.activeRouteId(),
                "端到端接管问题？", null, List.of(), true);
        AgentRun queued = runService.createQueuedRunWithInputResultForRoute(
                project.id(), "ANSWER_TIP", root.id(), null, List.<UUID>of(),
                "这是用户第一次给出的回答", null, null, null, null, null);
        UUID runId = queued.id();

        assertThat(tasks.findByWorkspaceRunId(runId))
                .as("multi-worker mode must register a runtime task in the enqueue transaction")
                .isPresent();

        // ── 2. Worker A 认领(持有存活租约)──
        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
                lifecyclePorts, scopeResolvers).orElseThrow();
        assertThat(claimA.runId()).isEqualTo(runId);

        // ── 3. 精确模拟"Answer 已提交、checkpoint 未写出"时的进程死亡 ──
        //    Answer 是不可变事实:由生产服务落库,并回填 Run 的产出引用与步骤账本。
        Answer persisted = txTemplate().execute(tx -> answers.finalizeAnswerWithSelections(
                project.id(), project.activeRouteId(), root.id(),
                List.of(), "这是用户第一次给出的回答", "user"));
        jdbc.update("UPDATE agent_runs SET status = 'persisted', produced_answer_id = :answerId "
                        + "WHERE id = :runId",
                Map.of("answerId", persisted.id(), "runId", runId));
        steps.prepare(runId, WorkspaceRunSteps.ANSWER_PERSISTED, null, claimA.token().epoch(),
                WorkspaceRunStepRetryClass.SAFE);
        steps.markSucceeded(runId, WorkspaceRunSteps.ANSWER_PERSISTED,
                claimA.token().epoch(), WorkspaceRunSteps.answerRef(persisted.id()));
        // 进程死亡:租约自然过期(不再心跳)。
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claimA.taskId()));
        long answersBefore = count("SELECT COUNT(*) FROM answers WHERE project_id = :p",
                Map.of("p", project.id()));
        long patchesBefore = count("SELECT COUNT(*) FROM answer_patches WHERE project_id = :p",
                Map.of("p", project.id()));
        assertThat(patchesBefore).as("the crash happened before any patch checkpoint").isZero();

        // ── 4. 存活实例做过期恢复:真实适配器核验 ──
        RuntimeTaskScheduler survivor = worker();
        int recovered = survivor.recoverExpiredLeases(lifecyclePorts, List.of(recoveryAdapter));
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // 判定必须安全(有可验证的 Answer、没有未知副作用),因此任务重新排队,
        // 而不是诚实失败。
        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code FROM runtime_tasks WHERE id = :id",
                Map.of("id", claimA.taskId()));
        assertThat(taskRow.get("state"))
                .as("a verified answer checkpoint is safe to take over")
                .isEqualTo("QUEUED");
        assertThat(taskRow.get("last_error_code"))
                .as("safe takeover must not record an outcome-unknown failure")
                .isNull();

        // ── 5. 旧 Worker 恢复后不能提交任何东西 ──
        var guard = new com.specagent.runtime.execution.TaskWriteGuard(jdbc,taskAuthorization);
        assertThat(guard.stillOwned(claimA.token()))
                .as("the dead worker's token must be invalid after takeover")
                .isFalse();

        // ── 6. 新 Worker 认领并接管执行(完整周期) ──
        //    恢复后的任务带有可配置的重排退避(available_at = now() + delay),
        //    这是刻意的背压:接管不应在租约刚刚过期时立刻抢占,避免多个实例
        //    同时争抢同一个刚失效的任务。因此这里按真实时间等待它变得可认领。
        RuntimeTaskScheduler workerB = worker();
        ExecutionClaim claimB = claimEventually(workerB, runId);
        assertThat(claimB.token().epoch())
                .as("takeover must strictly increase the execution epoch")
                .isGreaterThan(claimA.token().epoch());

        executionAdapter.execute(claimB);

        // ── 7. 核心断言:没有第二个 Answer,也没有第二个 Patch;周期真的继续了 ──
        assertThat(count("SELECT COUNT(*) FROM answers WHERE project_id = :p",
                Map.of("p", project.id())))
                .as("takeover must reuse the persisted immutable answer, never create a second")
                .isEqualTo(answersBefore)
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM answer_patches WHERE project_id = :p",
                Map.of("p", project.id())))
                .as("the cycle must continue from the verified checkpoint and write one patch")
                .isEqualTo(1);

        Map<String, Object> finalRun = jdbc.queryForMap(
                "SELECT status, produced_answer_id, produced_patch_id FROM agent_runs WHERE id = :id",
                Map.of("id", runId));
        assertThat(finalRun.get("status")).isEqualTo("completed");
        assertThat(finalRun.get("produced_answer_id"))
                .as("the answer identity must be the original one")
                .isEqualTo(persisted.id());

        // 步骤账本反映了真实的完成顺序。
        assertThat(steps.find(runId, WorkspaceRunSteps.ANSWER_PERSISTED).orElseThrow().state())
                .isEqualTo(WorkspaceRunStepState.SUCCEEDED);
        assertThat(steps.find(runId, WorkspaceRunSteps.PATCH_PERSISTED).orElseThrow().state())
                .isEqualTo(WorkspaceRunStepState.SUCCEEDED);
    }

    @Test
    void unknownModelStepStopsTakeoverAndKeepsTheAnswerAlone() {
        Project project = projects.createProject("未知副作用停止 " + UUID.randomUUID());
        touchedProjects.add(project.id());
        Node root = nodes.createRootNode(project.id(), project.activeRouteId(),
                "未知副作用问题？", null, List.of(), true);
        AgentRun queued = runService.createQueuedRunWithInputResultForRoute(
                project.id(), "ANSWER_TIP", root.id(), null, List.<UUID>of(),
                "另一个回答", null, null, null, null, null);
        UUID runId = queued.id();

        RuntimeTaskScheduler workerA = worker();
        ExecutionClaim claimA = workerA.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
                lifecyclePorts, scopeResolvers).orElseThrow();

        // 模拟:Answer 已落库,但 STATE_UPDATE 的模型调用结果未知(进程在外部调用
        // 之后、结果持久化之前死亡)。这正是 design §7 要求"默认不重放"的形态。
        Answer persisted = txTemplate().execute(tx -> answers.finalizeAnswerWithSelections(
                project.id(), project.activeRouteId(), root.id(), List.of(), "另一个回答", "user"));
        jdbc.update("UPDATE agent_runs SET status = 'persisted', produced_answer_id = :answerId "
                        + "WHERE id = :runId",
                Map.of("answerId", persisted.id(), "runId", runId));
        steps.prepare(runId, WorkspaceRunSteps.STATE_UPDATE_MODEL, null, claimA.token().epoch(),
                WorkspaceRunStepRetryClass.UNSAFE);
        steps.markUnknown(runId, WorkspaceRunSteps.STATE_UPDATE_MODEL,
                claimA.token().epoch(), null);
        jdbc.update("UPDATE runtime_tasks SET lease_until = now() - interval '1 second' "
                + "WHERE id = :id", Map.of("id", claimA.taskId()));

        RuntimeTaskScheduler survivor = worker();
        survivor.recoverExpiredLeases(lifecyclePorts, List.of(recoveryAdapter));

        Map<String, Object> taskRow = jdbc.queryForMap(
                "SELECT state, last_error_code, recovery_reason FROM runtime_tasks WHERE id = :id",
                Map.of("id", claimA.taskId()));
        assertThat(taskRow.get("state"))
                .as("an unknown non-replayable side effect must stop recovery")
                .isEqualTo("FAILED");
        assertThat(taskRow.get("last_error_code"))
                .isEqualTo("EXECUTION_OUTCOME_UNKNOWN");
        assertThat(String.valueOf(taskRow.get("recovery_reason")))
                .as("the operator must get a reconciliation record")
                .contains(WorkspaceRunSteps.STATE_UPDATE_MODEL);

        // 副作用没有被重放:补丁从未被写出,Answer 保持唯一且原样。
        assertThat(count("SELECT COUNT(*) FROM answer_patches WHERE project_id = :p",
                Map.of("p", project.id()))).isZero();
        assertThat(count("SELECT COUNT(*) FROM answers WHERE project_id = :p",
                Map.of("p", project.id()))).isEqualTo(1);
    }

    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(txManager);
    }

    /**
     * 等待任务越过恢复退避后可被认领。轮询是真实时间的(退避以数据库时钟表达),
     * 因此这里用有界轮询而不是 sleep 猜测。
     */
    private ExecutionClaim claimEventually(RuntimeTaskScheduler worker, UUID expectedRunId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var claim = worker.tryClaim(List.of(RuntimeTaskFamily.WORKSPACE),
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
                "task for run " + expectedRunId + " never became claimable again");
    }

    private long count(String sql, Map<String, Object> params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }
}
