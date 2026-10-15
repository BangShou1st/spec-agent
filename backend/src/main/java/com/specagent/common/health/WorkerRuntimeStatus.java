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

    /**
     * 是否运行在多 Worker 任务级所有权模式(design §9)。默认 false。
     * 该模式下没有全库租约,"租约丢失"不再是本进程的失败形态——执行可用性
     * 由任务级 token 与轮询开放状态表达。
     */
    default boolean isMultiWorkerTaskOwnership() {
        return false;
    }

    /**
     * 调度模式准入是否已永久丢失(design §11 的模式准入协议)。默认 false
     * (未启用准入或单执行器模式没有可丢失的会话锁)。
     */
    default boolean isExecutionModeAdmissionLost() {
        return false;
    }

    /** 模式准入丢失原因;未丢失时为 null。 */
    default String getExecutionModeAdmissionLostReason() {
        return null;
    }

    /**
     * 本进程的执行身份(design §9:日志与可观测性按 taskId/runId/workerId/epoch
     * 关联)。每次启动生成新 UUID;多 Worker 部署用它把 runtime_tasks.worker_id
     * 映射到具体实例。默认 null(无执行身份的进程)。
     */
    default String getWorkerId() {
        return null;
    }
}
