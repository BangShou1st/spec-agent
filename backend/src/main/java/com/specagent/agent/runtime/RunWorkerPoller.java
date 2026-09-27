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
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class RunWorkerPoller {

    private final RunWorker worker;
    private final WorkerPollingGate gate;

    public RunWorkerPoller(RunWorker worker, WorkerPollingGate gate) {
        this.worker = worker;
        this.gate = gate;
    }

    @Scheduled(fixedDelayString = "${spec.agent.brain.worker.poll-interval-ms:2000}")
    public void poll() {
        if (!gate.isPollingAllowed()) {
            return;
        }
        worker.tryClaimAndExecute();
        worker.recoverPendingContinuationChecks();
    }
}
