package com.specagent.agent.runtime;

import com.specagent.agent.decision.ModelResponse;

import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.route.RegenerateResult;

/**
 * 文件名:ReplacementRunResult.java
 *
 * 用途:已被接受的"模型驱动替换 proposal"的执行结果:替换产生的 run、
 * 冻结上下文快照、模型响应以及替换结果,打包返回给调用方。
 */
public record ReplacementRunResult(
        AgentRun run,
        ContextSnapshot contextSnapshot,
        ModelResponse modelResponse,
        RegenerateResult replacement) {
    public ReplacementRunResult {
        if (run == null || contextSnapshot == null || modelResponse == null || replacement == null) {
            throw new IllegalArgumentException("replacement run result fields are required");
        }
    }
}
