package com.specagent.agent.runtime;

import com.specagent.agent.decision.ModelResponse;

import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.spec.SpecSnapshot;

/**
 * 文件名:SpecRunResult.java
 *
 * 用途:成功的 spec 生成 run 的、与具体生产实现无关的结果载体:
 * run、冻结上下文快照、模型响应与产出的 SpecSnapshot。
 */
public record SpecRunResult(AgentRun run, ContextSnapshot contextSnapshot,
                            ModelResponse modelResponse, SpecSnapshot specSnapshot) {
    public SpecRunResult {
        if (run == null || contextSnapshot == null || modelResponse == null || specSnapshot == null) {
            throw new IllegalArgumentException("spec run result fields are required");
        }
    }
}
