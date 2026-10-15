package com.specagent.assistant.runtime;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskDispatcher;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RecoveryAdapter;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文件名:GaMultiWorkerDriver.java
 *
 * 用途:全局助手任务在多 Worker 模式下的认领与执行驱动(design §6.2、§7)。
 *
 * <p>与画布驱动并列、互不干扰:两者各自只认领自己族的任务,而族内容量槽
 * (GLOBAL_ASSISTANT)与范围({@code ga-thread:{threadId}})保证:
 * 不同会话可并行,同一会话串行(保留既有"至多一个活跃 Run"与 steer 语义)。
 *
 * <p><b>GA 恢复分两步。</b>本驱动只做第一步:持久化排队 + 多会话并行 + 按租约
 * 诚实终态化。经过验证的 checkpoint 恢复(第二步)需要显式的 {@link RecoveryAdapter};
 * 未注册适配器时租约过期一律诚实失败——绝不自动重放一次可能已经发出的模型调用。
 */
@Service
@ConditionalOnProperty(name = "spec.agent.execution.multi-worker-enabled", havingValue = "true")
public class GaMultiWorkerDriver implements com.specagent.runtime.execution.TaskQueueDriver {

    private static final Logger LOG = LoggerFactory.getLogger(GaMultiWorkerDriver.class);

    private static final Set<String> QUEUE = Set.of(GaTaskTypes.EXECUTION);

    private final RuntimeTaskScheduler scheduler;
    private final RuntimeTaskDispatcher dispatcher;
    private final List<TaskLifecyclePort> lifecyclePorts;
    private final List<TaskScopeResolver> scopeResolvers;
    private final List<RecoveryAdapter> recoveryAdapters;
    private final com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeats;

    public GaMultiWorkerDriver(RuntimeTaskScheduler scheduler,
                               RuntimeTaskDispatcher dispatcher,
                               List<TaskLifecyclePort> lifecyclePorts,
                               List<TaskScopeResolver> scopeResolvers,
                               List<RecoveryAdapter> recoveryAdapters,
                               com.specagent.runtime.execution.ExecutionHeartbeatRegistry heartbeats) {
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.lifecyclePorts = lifecyclePorts;
        this.scopeResolvers = scopeResolvers;
        this.recoveryAdapters = recoveryAdapters;
        this.heartbeats = heartbeats;
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.GLOBAL_ASSISTANT;
    }

    /** 本轮认领并执行至多一个 GA 任务。 */
    @Override
    public boolean claimAndExecuteOne() {
        Optional<ExecutionClaim> claim = scheduler.tryClaim(
                List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT), QUEUE,
                lifecyclePorts, scopeResolvers);
        if (claim.isEmpty()) {
            return false;
        }
        execute(claim.get());
        return true;
    }

    /**
     * 执行一个已认领的 GA 任务。绝不在数据库事务内发 HTTP:认领事务已经提交。
     */
    public void execute(ExecutionClaim claim) {
        var adapter = dispatcher.adapterFor(claim.taskType()).orElse(null);
        if (adapter == null) {
            LOG.error("No execution adapter for GA task type {}; failing task {} honestly",
                    claim.taskType(), claim.taskId());
            terminalize(claim, RuntimeTaskState.FAILED, "NO_EXECUTION_ADAPTER",
                    "no execution adapter registered for task type " + claim.taskType());
            return;
        }
        // 登记到独立心跳执行器:GA 执行在模型流/Python RPC 上阻塞时,租约仍被续租。
        heartbeats.register(claim.token());
        try {
            adapter.execute(claim);
        } catch (RuntimeException ex) {
            LOG.warn("GA task {} failed: {}", claim.taskId(), ex.getClass().getSimpleName());
            terminalize(claim, RuntimeTaskState.FAILED, "GA_EXECUTION_FAILED",
                    "execution failed: " + ex.getClass().getSimpleName());
            return;
        } finally {
            heartbeats.unregister(claim.token());
        }
        // 成功终态由协调器写业务事实(助手消息/事件/终态);任务在这里收口,
        // 使任务终态、attempt 结束、容量与范围释放一起提交。
        terminalize(claim, RuntimeTaskState.SUCCEEDED, null, null);
    }

    private void terminalize(ExecutionClaim claim, RuntimeTaskState state,
                             String errorCode, String reason) {
        try {
            scheduler.terminalize(claim, state, errorCode, reason, lifecyclePorts);
        } catch (com.specagent.runtime.execution.TaskOwnershipLostException lost) {
            LOG.warn("GA task {} was taken over before terminalization: {}",
                    claim.taskId(), lost.getMessage());
        }
    }

    /** 过期租约恢复(design §7)。 */
    @Override
    public int recoverExpiredLeases() {
        return scheduler.recoverExpiredLeases(lifecyclePorts, recoveryAdapters);
    }
}
