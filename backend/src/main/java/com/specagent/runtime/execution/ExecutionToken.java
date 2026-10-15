package com.specagent.runtime.execution;

import java.util.UUID;

/**
 * 文件名:ExecutionToken.java
 *
 * 用途:一次任务执行的显式所有权凭证。相对旧的"全库单执行器代次",本 token
 * 按任务隔离:每个任务独立租约、心跳与执行代次(docs/v2/
 * PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5.1、§6.3)。
 *
 * <p>token 是<b>声明</b>,不是权威:一切受保护写入都必须拿着 token 去数据库
 * 对 runtime_tasks 行取排他锁并逐一核实(state=LEASED、worker/epoch/lease
 * 三项匹配、租约与 deadline 未到期、未取消)。因此 token 即使在进程间传递
 * 或过期,也无法让旧执行器提交结果——权威只有数据库行。
 *
 * @param taskId      runtime_tasks.id
 * @param epoch       本次认领的执行代次(单调递增;首次为 1)
 * @param leaseId     本次认领生成的随机租约 id
 * @param workerId    执行它的进程实例 id(每次进程启动新生成)
 * @param family      任务族,决定引用哪张业务 Run 表
 * @param runId       归属的业务 Run id(agent_runs.id 或 global_assistant_runs.id)
 */
public record ExecutionToken(UUID taskId,
                             long epoch,
                             UUID leaseId,
                             UUID workerId,
                             RuntimeTaskFamily family,
                             UUID runId) {

    public ExecutionToken {
        if (taskId == null || leaseId == null || workerId == null
                || family == null || runId == null) {
            throw new IllegalArgumentException("Execution token fields must not be null");
        }
        if (epoch < 1) {
            throw new IllegalArgumentException(
                    "Execution token epoch must be >= 1; epoch 0 is not a valid claim");
        }
    }

    /** 日志用的短标识;绝不包含正文或凭证。 */
    public String describe() {
        return "task=" + taskId + " run=" + runId + " family=" + family
                + " epoch=" + epoch + " worker=" + workerId;
    }
}
