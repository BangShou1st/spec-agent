package com.specagent.runtime.execution;

/**
 * 文件名:TaskQueueDriver.java
 *
 * 用途:按任务族认领并执行的驱动端口(design §10)。
 *
 * <p><b>为什么需要这个中性端口。</b>轮询器位于 {@code com.specagent.agent},而
 * 全局助手的驱动位于 {@code com.specagent.assistant};若轮询器直接依赖后者,
 * 就会形成 {@code agent → assistant → agent} 的顶层模块环(ArchUnit 门禁禁止,
 * 且设计文档 §10 明确要求公共调度层"仅依赖公共基础设施和自己定义的端口")。
 * 因此轮询器只依赖本端口,由各业务包提供实现并按族注入。
 */
public interface TaskQueueDriver {

    /** 本驱动服务的任务族(诊断与装配自检用)。 */
    RuntimeTaskFamily family();

    /**
     * 本轮认领并执行至多一个任务。
     *
     * @return true 表示确实认领并处理了一个任务
     */
    boolean claimAndExecuteOne();

    /**
     * 收敛本族中租约已过期的任务(design §7)。
     *
     * @return 本轮完成判定的任务数
     */
    int recoverExpiredLeases();
}
