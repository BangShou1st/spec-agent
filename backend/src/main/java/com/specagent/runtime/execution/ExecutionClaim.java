package com.specagent.runtime.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:ExecutionClaim.java
 *
 * 用途:一次成功认领的结果:交给执行适配器的任务、业务 Run 与显式 token
 * (docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §6.2 第 5 步)。
 *
 * <p>认领事务提交之后才进入执行器,绝不在数据库事务内发 HTTP。
 */
public record ExecutionClaim(RuntimeTask task, ExecutionToken token, Instant leaseUntil) {

    public UUID taskId() {
        return task.id();
    }

    public UUID runId() {
        return task.runId();
    }

    public String taskType() {
        return task.taskType();
    }
}
