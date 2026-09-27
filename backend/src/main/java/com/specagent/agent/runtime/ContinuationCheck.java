package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * 文件名:ContinuationCheck.java
 *
 * 用途:一条待处理的续跑评估记录:终态 run 本身,加上它所对应的确切
 * 请求代数(generation)。
 *
 * 代数机制封堵了"审批后再请求"的 ABA 竞态:每次 {@code request(runId)}
 * 都会把代数加一,完成时只标记自己实际评估的那个代数。过期代数标记 0 行,
 * 较新的代数保持待处理——绝不可能悄悄完成另一个请求的工作。协调器仍会
 * 重新读取持久化的 run 事实来决策;本记录只承载路由身份,不含语义结论。
 */
public record ContinuationCheck(UUID runId, long generation) {

    public ContinuationCheck {
        if (runId == null) {
            throw new IllegalArgumentException("Continuation check requires a run id");
        }
        if (generation < 1) {
            throw new IllegalArgumentException(
                    "Continuation check generation must be >= 1, got " + generation);
        }
    }
}
