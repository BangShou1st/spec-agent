package com.specagent.common.health;

/**
 * 文件名:WorkerRuntimeStatus.java
 *
 * 用途:执行器轮询生命周期的只读健康视图。定义在 {@code common.health},
 * 使健康检查不依赖 agent 运行时包(架构规则:Runtime Kernel 不得依赖
 * Agent 层);由 {@code com.specagent.agent.runtime.WorkerPollingGate} 实现。
 *
 * 契约:worker 已启用的进程,健康检查必须能区分
 * - 正常服务(轮询开放),
 * - 启动中间态(轮询未开放:应用就绪前/孤儿恢复进行中,503),
 * - 恢复失败但队列存活(轮询开放 + recoveryError 非空,UP 但带告警字段),
 * - 执行器租约丢失(数据库互斥锁已不归本进程,轮询事实上停摆,503)。
 */
public interface WorkerRuntimeStatus {

    /** 轮询门控是否已开放(队列是否在消费)。 */
    boolean isPollingOpen();

    /** 启动孤儿恢复是否启用(说明当前启动形态)。 */
    boolean isStartupRecoveryEnabled();

    /** 启动恢复失败的错误摘要;正常时为 null。 */
    String getRecoveryError();

    /** 执行器租约是否已永久丢失;默认 false(无租约的进程不报丢失)。 */
    default boolean isExecutorLeaseLost() {
        return false;
    }

    /** 租约丢失原因;未丢失时为 null。 */
    default String getExecutorLeaseLostReason() {
        return null;
    }
}
