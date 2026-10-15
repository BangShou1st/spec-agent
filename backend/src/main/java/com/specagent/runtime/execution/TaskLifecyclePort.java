package com.specagent.runtime.execution;

import java.util.Optional;

/**
 * 文件名:TaskLifecyclePort.java
 *
 * 用途:公共调度层写业务状态的最小端口(design §10)。调度层不理解业务
 * Run 的语义,业务包实现本接口来承接两类写入:
 *
 * <ul>
 *   <li>{@link #onClaimed}:认领事务内把业务 Run 推进到运行态(design §6.2
 *       第 4 步)。它与任务认领在同一事务提交,绝不出现"任务已 LEASED 而
 *       业务 Run 仍是 CREATED"的对外可见状态。</li>
 *   <li>{@link #onTerminalized}:终态事务内写业务终态与终态事件(design
 *       §6.5)。它与任务终态、attempt 结束、容量与范围释放在同一事务提交,
 *       绝不出现"Run 已完成而任务可再次认领"。</li>
 * </ul>
 *
 * <p>两个回调都在<b>调度层的任务行排他锁之内</b>被调用,因此实现方不需要
 * 再做所有权协调;但必须保持幂等:终态回调可能因崩溃恢复被重放,既有终态
 * 绝不能被覆盖。
 */
public interface TaskLifecyclePort {

    /** 本端口处理的任务族。 */
    RuntimeTaskFamily family();

    /**
     * 认领成功后推进业务 Run 到运行态。必须幂等:重复调用不产生第二个运行
     * 事件。抛出异常即整体回滚认领。
     */
    void onClaimed(ExecutionClaim claim);

    /**
     * 任务终态化时写业务终态。必须保持已有终态不被覆盖(design §6.5):
     * 重复终态请求读取既有结果,不追加第二次终态事件。
     *
     * @param claim     认领上下文
     * @param taskState 任务终态(仅 SUCCEEDED / FAILED / CANCELLED)
     * @param errorCode 稳定错误码;成功时为 null
     * @param reason    可查询的失败/恢复原因;成功时为 null
     */
    void onTerminalized(ExecutionClaim claim, RuntimeTaskState taskState,
                        String errorCode, String reason);

    /**
     * 任务由过期租约进入核验后,业务侧需要知道的恢复判定(design §7)。
     * 默认无操作:调度层的恢复判定本身已经是完整事实。
     */
    default void onRecoveryDecision(RuntimeTask task, RecoveryDecision decision) {
    }
    /** Control-plane cancellation also applies to tasks that have never been leased. */
    default void onControlCancelled(RuntimeTask task) {
        onRecoveryDecision(task,RecoveryDecision.TERMINALIZED);
    }

    /** 恢复判定结果。 */
    enum RecoveryDecision {
        /** 安全接管,已重新排队。 */
        REQUEUED,
        /** 结果不确定,已诚实失败。 */
        FAILED_UNKNOWN,
        /** 超过尝试次数或已取消,已终态化。 */
        TERMINALIZED
    }

    /** 便利方法:按族取端口。 */
    static Optional<TaskLifecyclePort> select(java.util.List<TaskLifecyclePort> ports,
                                              RuntimeTaskFamily family) {
        return ports.stream().filter(port -> port.family() == family).findFirst();
    }
}
