package com.specagent.agent.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件名:RunWorkerPoller.java
 *
 * 用途:轮询循环,周期性认领排队 run 并交给 {@link RunWorker} 执行,
 * 同时恢复遗留的 pending continuation check。仅在 worker 开启时生效;
 * 确定性测试直接驱动 {@link RunWorker},不走本类。
 *
 * 启动顺序由 {@link WorkerPollingGate} 统一管理:应用就绪后,若启动恢复
 * 被显式关闭则轮询立即开放;若启用则等孤儿恢复收敛(或失败)后开放——
 * 绝不存在"worker 已启用但队列永久停摆"的静默状态。每个 tick 都要求
 * 执行器租约仍然在手。
 *
 * <p><b>多 Worker 模式(design §6.2、§6.4、§7)。</b>当
 * {@code spec.agent.execution.multi-worker-enabled=true} 时,认领改由
 * {@link WorkspaceMultiWorkerDriver} 通过公共调度层完成(每个 trigger 队列
 * 各认领一个任务,带容量槽与项目范围锁),本类同时承担:
 * <ul>
 *   <li>过期租约扫描 —— 撤销旧 token、核验后安全重排或诚实失败;</li>
 *   <li>pending continuation check 的兜底恢复。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerPoller {

    private final RunWorker worker;
    private final WorkerPollingGate gate;
    /**
     * 各族任务队列驱动(design §10)。轮询器只依赖中性端口
     * {@link com.specagent.runtime.execution.TaskQueueDriver},不依赖 assistant 包
     * ——否则会形成 agent → assistant → agent 的顶层模块环。
     */
    private final java.util.List<com.specagent.runtime.execution.TaskQueueDriver> queueDrivers;
    private final org.springframework.beans.factory.ObjectProvider<
            com.specagent.runtime.execution.LocalExecutionSlots> localSlots;

    public RunWorkerPoller(RunWorker worker,
                           WorkerPollingGate gate,
                           java.util.List<com.specagent.runtime.execution.TaskQueueDriver> queueDrivers,
                           org.springframework.beans.factory.ObjectProvider<
                                   com.specagent.runtime.execution.LocalExecutionSlots> localSlots) {
        this.worker = worker;
        this.gate = gate;
        this.queueDrivers = queueDrivers;
        this.localSlots = localSlots;
    }

    @Scheduled(fixedDelayString = "${spec.agent.brain.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!gate.isPollingAllowed()) {
            return;
        }
        if (queueDrivers.isEmpty()) {
            // 单执行器模式:历史行为完全不变。
            worker.tryClaimAndExecute();
        } else {
            claimViaScheduler();
        }
        worker.recoverPendingContinuationChecks();
    }

    /**
     * 多 Worker 认领:先确认本进程有实际空闲执行槽,再让调度层认领并执行。
     * 没有空闲槽就不认领——绝不把任务认领到本地却排队等待执行,那会让任务
     * 在租约内空转(design §6.2 第 1 步)。
     *
     * <p>各族的驱动依次尝试:每一族都有自己的容量上限,因此一种长任务不会占满
     * 整个系统(design §6.2 的轮换公平性)。
     */
    private void claimViaScheduler() {
        com.specagent.runtime.execution.LocalExecutionSlots slots = localSlots.getIfAvailable();
        if (slots != null && !slots.tryAcquire()) {
            return;
        }
        boolean claimed = false;
        try {
            for (var driver : queueDrivers) {
                if (driver.claimAndExecuteOne()) {
                    claimed = true;
                    break;
                }
            }
        } finally {
            if (slots != null) {
                slots.release();
            }
        }
        if (!claimed) {
            // 没有任务可认领时顺带做一次过期扫描:崩溃的 Worker 留下的租约由
            // 仍在运行的实例收敛(design §7)。多实例并发扫描由 SKIP LOCKED
            // 与锁后复核保证安全。
            for (var driver : queueDrivers) {
                driver.recoverExpiredLeases();
            }
        }
    }
}
