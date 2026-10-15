package com.specagent.runtime.execution;

/**
 * 文件名:RuntimeTaskState.java
 *
 * 用途:调度任务的生命周期状态。与业务 Run 的状态刻意分离:业务 Run 表达
 * 业务进度(created/running/context_built/...),本状态只表达"谁在什么时候
 * 有权执行"(docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5.1)。
 *
 * <p>QUEUED → LEASED →(SUCCEEDED | FAILED | CANCELLED)。
 * LEASED → RECOVERING → QUEUED(安全接管)或 FAILED(结果不确定)。
 */
public enum RuntimeTaskState {

    /** 可被认领。 */
    QUEUED,
    /** 已被某个 worker/epoch/lease 持有。 */
    LEASED,
    /** 租约过期后的核验状态:不得被认领,直到恢复事务判定安全。 */
    RECOVERING,
    /** 业务终态:成功。 */
    SUCCEEDED,
    /** 业务终态:失败(含诚实失败与结果不确定)。 */
    FAILED,
    /** 业务终态:取消。 */
    CANCELLED;

    public String code() {
        return name();
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public static RuntimeTaskState fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Runtime task state must not be null");
        }
        return RuntimeTaskState.valueOf(code.toUpperCase());
    }
}
