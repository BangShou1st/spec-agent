package com.specagent.agent.runtime;

import com.specagent.common.health.WorkerRuntimeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文件名:WorkerPollingGate.java
 *
 * 用途:worker 轮询的启动门控状态。"等待应用就绪"与"是否先执行孤儿恢复"
 * 是两个独立的事实:轮询只在"应用已就绪 && 恢复阶段已收敛(执行或显式跳过)"
 * 之后开放。启动恢复被显式关闭时,应用就绪即开放轮询——绝不允许出现
 * "worker 已启用但队列永久停摆"的静默状态;恢复失败同样开放轮询(队列
 * 活性优先),失败原因记录在案并由健康检查暴露。
 *
 * 只在 worker 启用的进程里存在;ExecutorLease 依赖本组件:所有权先于一切
 * 认领与恢复动作。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class WorkerPollingGate implements WorkerRuntimeStatus {

    private static final Logger log = LoggerFactory.getLogger(WorkerPollingGate.class);

    private final boolean startupRecoveryEnabled;
    private final ExecutorLease lease;

    private volatile boolean pollingOpen;
    private volatile String recoveryError;

    public WorkerPollingGate(
            @Value("${spec.agent.brain.worker.startup-recovery:true}") boolean startupRecoveryEnabled,
            ExecutorLease lease) {
        this.startupRecoveryEnabled = startupRecoveryEnabled;
        this.lease = lease;
    }

    /** 应用就绪且启动恢复关闭:立即开放轮询(不存在恢复阶段)。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyWhenRecoveryDisabled() {
        if (!startupRecoveryEnabled && !pollingOpen) {
            open("startup recovery disabled by configuration");
        }
    }

    /** 恢复入口在孤儿恢复收敛(或失败)后调用;错误信息仅记录,不阻塞队列。 */
    public void open(String trigger) {
        pollingOpen = true;
        log.info("Worker polling opened: {}", trigger);
    }

    public void recordRecoveryError(String error) {
        this.recoveryError = error;
    }

    /** 轮询是否已开放;同时要求执行器所有权仍然有效。 */
    public boolean isPollingAllowed() {
        return pollingOpen && lease.owned();
    }

    public boolean isPollingOpen() {
        return pollingOpen;
    }

    /** 启动恢复是否启用(供健康检查说明当前启动形态)。 */
    public boolean isStartupRecoveryEnabled() {
        return startupRecoveryEnabled;
    }

    public String getRecoveryError() {
        return recoveryError;
    }

    /** 健康检查:执行器租约是否已永久丢失。 */
    @Override
    public boolean isExecutorLeaseLost() {
        return lease.isLost();
    }

    /** 健康检查:租约丢失原因。 */
    @Override
    public String getExecutorLeaseLostReason() {
        return lease.getLostReason();
    }

    /** 供恢复入口确认所有权后执行恢复。 */
    public ExecutorLease lease() {
        return lease;
    }
}
