package com.specagent.agent.runtime;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import com.specagent.runtime.execution.TaskExecutionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文件名:WorkspaceMultiWorkerDriver.java
 *
 * 用途:画布任务在多 Worker 模式下的认领与执行驱动(design §6.2、§6.4)。
 *
 * <p>与单执行器 {@link RunWorker} 的分工:{@code RunWorker} 仍是<b>周期服务
 * 的执行器</b>(trigger 分发、失败终态化),本类是<b>调度器</b>——按每个队列
 * 各认领至多一个任务,逐个交给 {@link TaskExecutionAdapter} 执行业务周期。
 *
 * <p>两类队列轮换认领,避免一种长任务占满系统;每次扫描有界,繁忙项目不会
 * 挡住其他项目。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "spec.agent.execution.multi-worker-enabled", havingValue = "true")
public class WorkspaceMultiWorkerDriver
        implements com.specagent.runtime.execution.TaskQueueDriver {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceMultiWorkerDriver.class);

    /**
     * 认领顺序。同一族内按 trigger 队列逐个尝试,保证 DECISION / ANSWER /
     * ARTIFACT / REGENERATE / NODE_QUERY / CONTINUE 六条队列都不会被冷落
     * (每轮每个队列各至多认领一个任务)。
     *
     * <p>字面量与 {@link AgentRunTriggerType} 的 {@code code()} 保持一致
     * (小写下划线);task_type 就是 trigger code。
     */
    private static final List<Set<String>> TRIGGER_QUEUES = List.of(
            Set.of(AgentRunTriggerType.DECISION_CYCLE.code()),
            Set.of(AgentRunTriggerType.ANSWER_CYCLE.code()),
            Set.of(AgentRunTriggerType.GENERATE_SPEC.code()),
            Set.of(AgentRunTriggerType.REGENERATE_NODE.code()),
            Set.of(AgentRunTriggerType.NODE_QUERY.code()),
            Set.of(AgentRunTriggerType.CONTINUE_CYCLE.code()));

    private final RuntimeTaskScheduler scheduler;
    private final com.specagent.runtime.execution.RuntimeTaskDispatcher dispatcher;
    private final List<TaskLifecyclePort> lifecyclePorts;
    private final List<TaskScopeResolver> scopeResolvers;
    private final List<com.specagent.runtime.execution.RecoveryAdapter> recoveryAdapters;
    private final RuntimeTaskRepository tasks;
    private final com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeats;

    public WorkspaceMultiWorkerDriver(RuntimeTaskScheduler scheduler,
                                      com.specagent.runtime.execution.RuntimeTaskDispatcher dispatcher,
                                      List<TaskLifecyclePort> lifecyclePorts,
                                      List<TaskScopeResolver> scopeResolvers,
                                      List<com.specagent.runtime.execution.RecoveryAdapter> recoveryAdapters,
                                      RuntimeTaskRepository tasks,
                                      com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeats) {
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.lifecyclePorts = lifecyclePorts;
        this.scopeResolvers = scopeResolvers;
        this.recoveryAdapters = recoveryAdapters;
        this.tasks = tasks;
        this.heartbeats = heartbeats;
    }

    /**
     * 本轮认领并执行至多一个画布任务。
     *
     * @return true 表示确实认领并执行了一个任务
     */
    @Override
    public boolean claimAndExecuteOne() {
        for (Set<String> queue : TRIGGER_QUEUES) {
            // 按 task_type 过滤认领:每个 trigger 队列独立公平,不互相饿死。
            Optional<ExecutionClaim> claim = scheduler.tryClaim(
                    List.of(RuntimeTaskFamily.WORKSPACE), queue,
                    lifecyclePorts, scopeResolvers);
            if (claim.isEmpty()) {
                continue;
            }
            execute(claim.get());
            return true;
        }
        return false;
    }

    /**
     * 执行一个已认领的任务。<b>绝不在数据库事务内发 HTTP</b>:认领事务已经
     * 提交,这里只做执行与终态化。
     */
    public void execute(ExecutionClaim claim) {
        TaskExecutionAdapter adapter = dispatcher.adapterFor(claim.taskType()).orElse(null);
        if (adapter == null) {
            // fail-closed:没有适配器就没有执行。诚实失败,绝不静默成功。
            LOG.error("No execution adapter for task type {}; failing task {} honestly",
                    claim.taskType(), claim.taskId());
            terminalizeFailure(claim, "NO_EXECUTION_ADAPTER",
                    "no execution adapter registered for task type " + claim.taskType());
            return;
        }
        // 登记到独立心跳执行器:长周期在执行线程里阻塞时,租约仍被按时续租。
        heartbeats.register(claim.token());
        try {
            adapter.execute(claim);
        } catch (WorkspaceTaskExecutionAdapter.RunTerminalizedAsFailedException cycleFailed) {
            // 业务周期已经把 Run 终态化为 FAILED(例如 stale target、模型契约
            // 违规):业务失败原因已经落库,任务按失败收口但不覆盖业务原因。
            LOG.info("Workspace task {} cycle terminalized its run as failed: {}",
                    claim.taskId(), cycleFailed.getMessage());
            terminalizeFailure(claim, "RUN_TERMINALIZED_FAILED",
                    "the agent cycle terminalized its run as failed");
            return;
        } catch (RuntimeException ex) {
            LOG.warn("Workspace task {} ({}) failed: {}", claim.taskId(), claim.taskType(),
                    ex.getClass().getSimpleName());
            terminalizeFailure(claim, RunFailureReasons.reasonCode(ex),
                    "execution failed: " + ex.getClass().getSimpleName());
            return;
        } finally {
            heartbeats.unregister(claim.token());
        }
        // 业务周期自行完成终态(它写业务 Run 终态与事件);调度任务的成功终态
        // 在这里收口,使终态、attempt 结束、容量与范围释放一起提交。
        try {
            scheduler.terminalize(claim,
                    com.specagent.runtime.execution.RuntimeTaskState.SUCCEEDED,
                    null, null, lifecyclePorts);
        } catch (com.specagent.runtime.execution.TaskOwnershipLostException lost) {
            LOG.warn("Task {} was taken over before success terminalization: {}",
                    claim.taskId(), lost.getMessage());
        }
    }

    private void terminalizeFailure(ExecutionClaim claim, String errorCode, String reason) {
        try {
            scheduler.terminalize(claim,
                    com.specagent.runtime.execution.RuntimeTaskState.FAILED,
                    errorCode, reason, lifecyclePorts);
        } catch (com.specagent.runtime.execution.TaskOwnershipLostException lost) {
            LOG.warn("Task {} was taken over before failure terminalization: {}",
                    claim.taskId(), lost.getMessage());
        }
    }

    /**
     * 运行该任务的业务 Run 当前是否仍是可执行状态;供适配器在长任务开始前
     * 复核(避免执行一个已被取消的 Run)。
     */
    public boolean stillExecutable(ExecutionClaim claim) {
        RuntimeTask task = tasks.findById(claim.taskId()).orElse(null);
        return task != null && !task.isCancellationRequested();
    }

    /** 过期租约恢复(design §7):由轮询器周期性调用。 */
    @Override
    public int recoverExpiredLeases() {
        return scheduler.recoverExpiredLeases(lifecyclePorts, recoveryAdapters);
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.WORKSPACE;
    }

    /** 当前已注册的自动恢复适配器 task_type(诊断与健康检查用)。 */
    public java.util.Set<String> recoverableTaskTypes() {
        java.util.Set<String> types = new java.util.TreeSet<>();
        for (com.specagent.runtime.execution.RecoveryAdapter adapter : recoveryAdapters) {
            types.add(adapter.taskType());
        }
        return types;
    }

    /** 全局容量槽键(诊断/健康检查用)。 */
    public String globalSlot() {
        return RuntimeExecutionProtocol.SLOT_GLOBAL;
    }
}
