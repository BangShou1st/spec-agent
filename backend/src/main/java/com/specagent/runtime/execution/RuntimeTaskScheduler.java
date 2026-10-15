package com.specagent.runtime.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:RuntimeTaskScheduler.java
 *
 * 用途:持久化调度层的核心(design §6)。实现四件事:
 *
 * <ol>
 *   <li><b>认领</b>({@link #tryClaim}):在一个事务里按固定顺序取得容量槽与
 *       业务范围,推进任务为 LEASED、递增执行代次并记录 attempt,同时把业务
 *       Run 推进到运行态;提交后才返回给执行器,绝不在事务内发 HTTP。</li>
 *   <li><b>心跳</b>({@link #heartbeat}):条件续租;过期租约绝不复活,
 *       deadline 不因续租增加。</li>
 *   <li><b>终态</b>({@link #terminalize}):任务终态、业务终态、attempt 结束、
 *       容量与范围释放在同一事务提交。</li>
 *   <li><b>过期恢复</b>({@link #recoverExpiredLeases}):撤销旧 token,
 *       核验持久化事实后要么安全重排、要么诚实失败。</li>
 * </ol>
 *
 * <p><b>锁顺序(全局统一,不可颠倒)</b>:task 行 → 容量槽(按键排序) →
 * scope 行(按键排序) → 业务 Run → 项目/会话与业务行。没有涉及的锁可以
 * 跳过;范围有存活占用者时<b>立即</b>回滚候选并扫描其他候选,绝不持锁等待
 * 另一个任务释放范围。
 */
@Service
public class RuntimeTaskScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeTaskScheduler.class);

    private final RuntimeTaskRepository tasks;
    private final RuntimeExecutionSettings settings;
    private final WorkerIdentity worker;
    private final TransactionTemplate transactionTemplate;

    public RuntimeTaskScheduler(RuntimeTaskRepository tasks,
                                RuntimeExecutionSettings settings,
                                WorkerIdentity worker,
                                TransactionTemplate transactionTemplate) {
        this.tasks = tasks;
        this.settings = settings;
        this.worker = worker;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 尝试认领一个任务。返回空表示本轮没有可执行任务(没有候选、容量或范围
     * 竞争失败)。
     *
     * <p>调用方必须<b>先</b>取得 {@link LocalExecutionSlots} 的空闲槽:没有
     * 空闲槽时不应调用本方法,否则任务会在租约内空转等待本地执行。
     *
     * @param familiesInOrder 按顺序尝试的任务族(两类队列轮换,避免饥饿)
     */
    public Optional<ExecutionClaim> tryClaim(List<RuntimeTaskFamily> familiesInOrder,
                                             List<TaskLifecyclePort> lifecyclePorts,
                                             List<TaskScopeResolver> resolvers) {
        return tryClaim(familiesInOrder, java.util.Set.of(), lifecyclePorts, resolvers);
    }

    /**
     * 尝试认领一个任务(可按 task_type 过滤,供处理指定队列的调用方使用)。
     *
     * @param taskTypes 允许的 task_type;空集表示不限
     */
    public Optional<ExecutionClaim> tryClaim(List<RuntimeTaskFamily> familiesInOrder,
                                             java.util.Set<String> taskTypes,
                                             List<TaskLifecyclePort> lifecyclePorts,
                                             List<TaskScopeResolver> resolvers) {
        for (RuntimeTaskFamily family : familiesInOrder) {
            Optional<ExecutionClaim> claim = tryClaimFamily(family, taskTypes,
                    lifecyclePorts, resolvers);
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    private Optional<ExecutionClaim> tryClaimFamily(RuntimeTaskFamily family,
                                                    java.util.Set<String> taskTypes,
                                                    List<TaskLifecyclePort> lifecyclePorts,
                                                    List<TaskScopeResolver> resolvers) {
        // 先确认全局容量,避免为注定失败的认领开事务。
        if (tasks.availableGlobalSlots() <= 0) {
            return Optional.empty();
        }
        List<String> slotKeys = List.of(RuntimeExecutionProtocol.SLOT_GLOBAL,
                RuntimeExecutionProtocol.familySlot(family));

        // 逐个候选尝试:每个候选独立事务,范围冲突只回滚自己,不阻塞其他项目
        // 的候选,也绝不等待另一个任务释放范围(这是"繁忙项目不挡住其他项目"
        // 的关键)。
        Set<UUID> attempted = new HashSet<>();
        for (int scan = 0; scan < settings.getClaimScanLimit(); scan++) {
            ClaimOutcome outcome;
            try {
                outcome = transactionTemplate.execute(tx ->
                        claimOneCandidate(family, taskTypes, slotKeys, attempted,
                                lifecyclePorts, resolvers));
            } catch (CapacityUnavailable rejected) {
                // 事务已回滚,candidate 仍是 QUEUED。必须把它的 id 记进
                // attempted(Java 侧集合不受回滚影响),否则下一轮会再次选中
                // 同一个候选直到扫描上限,永远轮不到后面可执行的项目。
                attempted.add(rejected.taskId);
                continue;
            }
            if (outcome == null || outcome.claim() == null) {
                return Optional.empty();
            }
            if (outcome.claim().token() != null) {
                return Optional.of(outcome.claim());
            }
            attempted.add(outcome.claim().taskId());
            LOG.debug("Task {} scope is busy; scanning the next candidate",
                    outcome.claim().taskId());
        }
        return Optional.empty();
    }

    /**
     * 在单个事务内认领一个候选。返回带 token 的 claim 表示成功;返回 token 为
     * null 的 claim 表示该候选的范围被存活占用者持有,调用方继续扫描;返回
     * null 表示没有更多候选。
     *
     * <p>容量不足抛出 {@link CapacityUnavailable}:它必须回滚<b>整个</b>候选
     * 事务,否则已取得的部分容量会泄漏在失败候选上。
     */
    private ClaimOutcome claimOneCandidate(RuntimeTaskFamily family,
                                           java.util.Set<String> taskTypes,
                                           List<String> slotKeys,
                                           Set<UUID> attempted,
                                           List<TaskLifecyclePort> lifecyclePorts,
                                           List<TaskScopeResolver> resolvers) {
        Optional<RuntimeTask> candidate = tasks.lockNextQueuedCandidate(family, taskTypes, attempted);
        if (candidate.isEmpty()) {
            return null;
        }
        RuntimeTask task = candidate.get();
        UUID taskId = task.id();

        // 先解析业务范围:范围解析失败按 fail-closed 处理——绝不认领一个
        // 无法确定执行边界的任务。
        Optional<TaskScopeResolver> resolver = resolvers.stream()
                .filter(candidateResolver -> candidateResolver.family() == family).findFirst();
        if (resolver.isEmpty()) {
            return new ClaimOutcome(new ExecutionClaim(task, null, null));
        }
        Optional<String> scope = resolver.get().resolveScope(task);
        if (scope.isEmpty()) {
            return new ClaimOutcome(new ExecutionClaim(task, null, null));
        }

        // 锁序第 2 步:容量槽(按键排序)。
        if (!tasks.tryAcquireCapacity(slotKeys)) {
            throw new CapacityUnavailable(taskId);
        }

        // 锁序第 1 步的续:推进任务为 LEASED。代次由数据库在 UPDATE 内递增,
        // 因此并发认领绝不可能算出同一个代次;返回行给出本行的真实 token。
        ExecutionToken provisional = new ExecutionToken(taskId, 1L, UUID.randomUUID(),
                worker.id(), family, task.runId());
        Optional<RuntimeTask> leased = tasks.lease(taskId, provisional,
                settings.getLeaseSeconds(), settings.timeoutSecondsFor(family));
        if (leased.isEmpty()) {
            throw new CapacityUnavailable(taskId);
        }
        ExecutionToken token = leased.get().token();
        if (token == null) {
            throw new IllegalStateException(
                    "leased runtime task " + taskId + " has no ownership token");
        }

        // 锁序第 3 步:业务范围(按键排序)。范围被存活占用者持有即回滚整个
        // 候选,继续扫描下一个候选——绝不等待它跑完。
        if (!tasks.tryAcquireScopes(taskId, token, List.of(scope.get()))) {
            throw new CapacityUnavailable(taskId);
        }
        for (String slotKey : slotKeys) {
            tasks.bindCapacity(taskId, slotKey);
        }
        tasks.insertAttempt(UUID.randomUUID(), token);

        ExecutionClaim claim = new ExecutionClaim(leased.get(), token, leased.get().leaseUntil());
        TaskLifecyclePort port = TaskLifecyclePort.select(lifecyclePorts, family).orElse(null);
        if (port != null) {
            // 业务 Run 推进与任务认领同一事务提交:不会出现"任务已 LEASED 而
            // 业务 Run 仍 CREATED"的对外可见状态。
            port.onClaimed(claim);
        }
        return new ClaimOutcome(claim);
    }

    private record ClaimOutcome(ExecutionClaim claim) {
    }

    /** 容量或范围不可用:回滚当前候选事务的内部信号,携带候选 id 供调用方排除。 */
    static final class CapacityUnavailable extends RuntimeException {
        private final UUID taskId;

        CapacityUnavailable(UUID taskId) {
            super("execution capacity or scope unavailable for task " + taskId,
                    null, false, false);
            this.taskId = taskId;
        }

        UUID taskId() {
            return taskId;
        }
    }

    /**
     * 心跳续租(design §6.4)。在一个短事务里条件续租:先取任务行锁,再以实时
     * 数据库时钟核实 token 三项匹配、仍是 LEASED、未取消、旧租约仍有效;过期
     * 租约不能复活。deadline 不因续租增加。
     *
     * <p><b>返回值契约:</b>{@code false} <b>仅</b>表示已失去所有权(条件续租
     * 落空或数据库不可达)——调用方必须立即停止发起新步骤并尽力取消远端执行。
     * 锁等待/语句超时(数据库仍可达、租约可能仍有效)返回 {@code true},由下一轮
     * 心跳重试,<b>绝不据此判定失权</b>(否则一次锁竞争就会误杀在途执行)。
     *
     * @return true 表示租约仍然有效(已续租或暂态可重试);false 表示已失去所有权
     */
    public boolean heartbeat(ExecutionToken token) {
        try {
            Integer renewed = transactionTemplate.execute(tx ->
                    tasks.renewLease(token, settings.getLeaseSeconds()));
            return renewed != null && renewed == 1;
        } catch (org.springframework.dao.CannotAcquireLockException
                 | org.springframework.dao.QueryTimeoutException deferred) {
            // 锁等待/语句超时:租约可能仍然有效。绝不据此判定失权,下一轮重试。
            LOG.warn("Heartbeat deferred for {}: {}", token.describe(), deferred.getMessage());
            return true;
        } catch (RuntimeException ex) {
            // 数据库不可达即判定未续租成功:明确终止执行,绝不在网络恢复后
            // 自作主张沿用旧 token。
            LOG.warn("Heartbeat failed for {}: {}", token.describe(), ex.getMessage());
            return false;
        }
    }

    /**
     * 任务终态化(design §6.5)。任务终态、业务终态、attempt 结束、容量与
     * 范围释放在<b>同一事务</b>提交;重复终态请求读取既有结果,不追加第二次
     * 终态。
     *
     * @return true 表示本次调用真正完成了终态化;false 表示任务已终态(幂等
     *         no-op)
     * @throws TaskOwnershipLostException 本执行器已不再拥有该任务(接管已发生)
     */
    public boolean terminalize(ExecutionClaim claim,
                               RuntimeTaskState terminalState,
                               String errorCode,
                               String reason,
                               List<TaskLifecyclePort> lifecyclePorts) {
        ExecutionToken token = claim.token();
        Boolean applied = transactionTemplate.execute(tx -> {
            Optional<RuntimeTask> locked = tasks.lockById(token.taskId());
            if (locked.isEmpty()) {
                return false;
            }
            RuntimeTask task = locked.get();
            if (task.state().isTerminal()) {
                // 幂等 no-op:绝不追加第二次终态,绝不覆盖既有终态。
                return false;
            }
            if (task.state() != RuntimeTaskState.LEASED
                    || !token.workerId().equals(task.workerId())
                    || token.epoch() != task.executionEpoch()
                    || !token.leaseId().equals(task.leaseId())) {
                throw new TaskOwnershipLostException(
                        "cannot terminalize " + token.describe() + "; task is "
                                + task.state() + " (takeover already happened)");
            }
            tasks.endAttempt(task.id(), token.epoch(), terminalState.code(), reason);
            tasks.terminalize(token, terminalState, errorCode, reason);
            tasks.releaseScopes(task.id());
            tasks.releaseCapacity(task.id());
            TaskLifecyclePort port = TaskLifecyclePort.select(lifecyclePorts, task.family())
                    .orElse(null);
            if (port != null) {
                port.onTerminalized(claim, terminalState, errorCode, reason);
            }
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /**
     * 本地执行器提交失败(design §6.2 第 6 步):由该 token 条件释放认领并
     * 重新排队,绝不盲目标记业务失败。
     */
    public boolean requeueAfterLocalFailure(ExecutionToken token, String reason) {
        Boolean requeued = transactionTemplate.execute(tx -> {
            if (tasks.requeueAfterLocalFailure(token, settings.getRequeueDelaySeconds(),
                    reason) != 1) {
                return false;
            }
            tasks.endAttempt(token.taskId(), token.epoch(), "REQUEUED", reason);
            tasks.releaseScopes(token.taskId());
            tasks.releaseCapacity(token.taskId());
            return true;
        });
        return Boolean.TRUE.equals(requeued);
    }

    /**
     * 过期租约恢复(design §7)。多个实例周期扫描,用 SKIP LOCKED 分批处理;
     * 锁 task 后<b>再次</b>核实过期,然后撤销旧 token、标记旧 attempt 结束并
     * 进入 RECOVERING。
     *
     * <p>恢复不依赖 worker 是否已从注册表消失。恢复检查只访问持久化数据,
     * 保持短事务。
     *
     * @return 本轮完成判定的任务数
     */
    public int recoverExpiredLeases(List<TaskLifecyclePort> lifecyclePorts,
                                    List<RecoveryAdapter> recoveryAdapters) {
        Integer recovered = transactionTemplate.execute(tx -> {
            // 容量计数自愈:以占用绑定行为唯一事实修正冗余计数。漂移会让执行槽
            // 被永久吞噬,最终整个系统不再认领任何任务(design §5.4)。
            tasks.reconcileCapacity();
            List<RuntimeTask> expired = tasks.scanExpiredLeases(settings.getExpiryScanBatch());
            int count = 0;
            for (RuntimeTask task : expired) {
                try {
                    if (recoverOne(task, lifecyclePorts, recoveryAdapters)) {
                        count++;
                    }
                } catch (RuntimeException ex) {
                    LOG.warn("Expired-lease recovery failed for task {}: {}",
                            task.id(), ex.getMessage());
                }
            }
            return count;
        });
        return recovered == null ? 0 : recovered;
    }

    private boolean recoverOne(RuntimeTask task,
                               List<TaskLifecyclePort> lifecyclePorts,
                               List<RecoveryAdapter> recoveryAdapters) {
        // 再次核实过期:扫描与本次锁之间可能已被其他实例接管。
        if (tasks.beginRecovery(task.id(), task.executionEpoch(), "LEASE_EXPIRED") != 1) {
            return false;
        }
        tasks.endAttempt(task.id(), task.executionEpoch(), "LEASE_EXPIRED",
                "lease expired before completion");
        tasks.releaseScopes(task.id());
        tasks.releaseCapacity(task.id());

        RuntimeTask recovering = tasks.findById(task.id()).orElse(task);
        TaskLifecyclePort port = TaskLifecyclePort.select(lifecyclePorts, task.family())
                .orElse(null);

        // 取消 / 尝试次数耗尽:直接终态化,不做任何恢复尝试。
        if (recovering.isCancellationRequested()) {
            tasks.failFromRecovery(task.id(), "CANCELLED_DURING_RECOVERY",
                    "cancellation was requested before the lease expired");
            notifyRecovery(port, recovering, TaskLifecyclePort.RecoveryDecision.TERMINALIZED);
            return true;
        }
        if (recovering.attemptCount() >= recovering.maxAttempts()) {
            tasks.failFromRecovery(task.id(), "EXECUTION_ATTEMPTS_EXHAUSTED",
                    "attempt budget exhausted after " + recovering.attemptCount() + " attempts");
            notifyRecovery(port, recovering, TaskLifecyclePort.RecoveryDecision.TERMINALIZED);
            return true;
        }

        // 只有提供 RecoveryAdapter 且通过故障测试的 task_type 才可以自动恢复;
        // 其余使用租约过期后的诚实失败。
        RecoveryAdapter adapter = recoveryAdapters.stream()
                .filter(candidate -> candidate.taskType().equals(recovering.taskType()))
                .findFirst().orElse(null);
        RecoveryVerdict verdict = adapter == null
                ? RecoveryVerdict.unsafe("no verified recovery adapter for task type "
                        + recovering.taskType()
                        + "; late writes of the expired owner are already refused",
                        "task=" + recovering.id() + " run=" + recovering.runId()
                                + " attempt=" + recovering.attemptCount())
                : adapter.assess(recovering);

        if (verdict.safe()) {
            tasks.requeueFromRecovery(task.id(), settings.getRecoveryRequeueDelaySeconds(),
                    verdict.reason());
            notifyRecovery(port, recovering, TaskLifecyclePort.RecoveryDecision.REQUEUED);
            return true;
        }
        tasks.failFromRecovery(task.id(), "EXECUTION_OUTCOME_UNKNOWN",
                verdict.reconciliationRecord() == null
                        ? verdict.reason()
                        : verdict.reason() + " | " + verdict.reconciliationRecord());
        notifyRecovery(port, recovering, TaskLifecyclePort.RecoveryDecision.FAILED_UNKNOWN);
        return true;
    }

    private void notifyRecovery(TaskLifecyclePort port, RuntimeTask task,
                                TaskLifecyclePort.RecoveryDecision decision) {
        if (port == null) {
            return;
        }
        try {
            port.onRecoveryDecision(task, decision);
        } catch (RuntimeException ex) {
            LOG.warn("Recovery notification failed for task {}: {}", task.id(), ex.getMessage());
        }
    }

    /** 本进程当前持有的在途任务(关闭 drain 与指标使用)。 */
    public List<RuntimeTask> tasksHeldByThisWorker() {
        List<RuntimeTask> held = new ArrayList<>(tasks.scanHeldByWorker(worker.id()));
        return held;
    }

    /**
     * 取消任务(design §6.5):锁 task 后撤销当前 token、写取消状态,释放容量
     * 与范围。正在进行的外部调用只能尽力停止;已提交事实与已发生的外部副作用
     * 不能宣称已撤销。
     */
    public boolean cancel(UUID taskId, List<TaskLifecyclePort> lifecyclePorts) {
        return cancelIf(taskId,lifecyclePorts,task -> true);
    }
    public boolean cancelIf(UUID taskId,List<TaskLifecyclePort> lifecyclePorts,
                            java.util.function.Predicate<RuntimeTask> predicate) {
        Boolean cancelled = transactionTemplate.execute(tx -> {
            Optional<RuntimeTask> locked = tasks.lockById(taskId);
            if (locked.isEmpty() || locked.get().state().isTerminal()) {
                return false;
            }
            RuntimeTask task = locked.get();
            tasks.lockOwnedResources(taskId);
            if (!predicate.test(task)) return false;
            if (tasks.cancel(taskId) != 1) {
                return false;
            }
            if (task.state() == RuntimeTaskState.LEASED) {
                tasks.endAttempt(taskId, task.executionEpoch(), "CANCELLED",
                        "cancelled by the control plane");
            }
            tasks.releaseScopes(taskId);
            tasks.releaseCapacity(taskId);
            TaskLifecyclePort port = TaskLifecyclePort.select(lifecyclePorts, task.family())
                    .orElse(null);
            if (port != null && task.state() == RuntimeTaskState.LEASED) {
                port.onTerminalized(new ExecutionClaim(task, task.token(), task.leaseUntil()),
                        RuntimeTaskState.CANCELLED, "TASK_CANCELLED",
                        "cancelled by the control plane");
            } else if (port != null) {
                port.onControlCancelled(task);
            }
            return true;
        });
        return Boolean.TRUE.equals(cancelled);
    }

    /** 请求取消但保留 token 与所有权,由执行侧自行收敛(协作式取消)。 */
    public boolean requestCancel(UUID taskId) {
        return tasks.requestCancel(taskId) == 1;
    }
}
