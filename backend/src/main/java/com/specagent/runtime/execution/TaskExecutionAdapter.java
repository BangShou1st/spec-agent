package com.specagent.runtime.execution;

/**
 * 文件名:TaskExecutionAdapter.java
 *
 * 用途:公共调度层与业务执行之间的唯一端口(design §10)。调度层不认识
 * 画布周期服务,也不认识全局助手协调器;业务包各自实现本接口,由调度层按
 * task_type 分派。
 *
 * <p>契约:
 * <ul>
 *   <li>执行器在<b>数据库事务之外</b>被调用,绝不在事务内发 HTTP;</li>
 *   <li>token 必须显式向下传递(参数或执行上下文,不是唯一的线程局部变量),
 *       一切受保护写入以 token 去数据库核实所有权;</li>
 *   <li>正常返回即业务终态化完成;抛出异常表示本次执行失败,由调用方按失败
 *       协议处理(诚实失败或重排)。</li>
 * </ul>
 */
public interface TaskExecutionAdapter {

    /** 本适配器负责的 taskType 集合(对应业务 trigger 或 GA_EXECUTION)。 */
    java.util.Set<String> taskTypes();

    /**
     * 执行一次已认领的任务。
     *
     * @param claim 认领结果(含任务、业务 Run 与显式 token)
     */
    void execute(ExecutionClaim claim);
}
