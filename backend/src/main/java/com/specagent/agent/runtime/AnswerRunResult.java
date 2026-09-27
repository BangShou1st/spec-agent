package com.specagent.agent.runtime;

import com.specagent.agent.decision.ModelResponse;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.patch.AnswerPatch;

/**
 * 文件名:AnswerRunResult.java
 *
 * 用途:成功完成的回答处理 run 的、与具体生产实现无关的结果载体:
 * run、冻结快照、三次模型响应(解释/补丁/节点)以及产出的 Answer、
 * AnswerPatch 与 Node。
 */
public record AnswerRunResult(AgentRun run, ContextSnapshot contextSnapshot,
                              ModelResponse interpretResponse, ModelResponse patchResponse,
                              ModelResponse nodeResponse, Answer answer, AnswerPatch patch,
                              Node producedNode) {
    public AnswerRunResult {
        if (run == null || contextSnapshot == null || interpretResponse == null
                || patchResponse == null || nodeResponse == null || answer == null
                || patch == null || producedNode == null) {
            throw new IllegalArgumentException("answer run result fields are required");
        }
    }
}
