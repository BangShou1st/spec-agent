package com.specagent.agent;

import com.specagent.agent.decision.ModelResponse;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.patch.AnswerPatch;

/**
 * 文件名:FakeAnswerRunResult.java
 *
 * 测试目标:一次 fake 答题 run 的结果承载:不可变答案、有依据的答案 patch、
 * 以及答案之后起草的下一个节点。run 成功完成时所有字段必填且非空。三个模型响应
 * 对应循环中的三次模型调用:解读答案、起草答案 patch、起草下一个节点。
 */
public record FakeAnswerRunResult(
        AgentRun run,
        ContextSnapshot contextSnapshot,
        ModelResponse interpretResponse,
        ModelResponse patchResponse,
        ModelResponse nodeResponse,
        Answer answer,
        AnswerPatch patch,
        Node producedNode
) {
    public FakeAnswerRunResult {
        if (run == null) {
            throw new IllegalArgumentException("run is required");
        }
        if (contextSnapshot == null) {
            throw new IllegalArgumentException("contextSnapshot is required");
        }
        if (interpretResponse == null) {
            throw new IllegalArgumentException("interpretResponse is required");
        }
        if (patchResponse == null) {
            throw new IllegalArgumentException("patchResponse is required");
        }
        if (nodeResponse == null) {
            throw new IllegalArgumentException("nodeResponse is required");
        }
        if (answer == null) {
            throw new IllegalArgumentException("answer is required");
        }
        if (patch == null) {
            throw new IllegalArgumentException("patch is required");
        }
        if (producedNode == null) {
            throw new IllegalArgumentException("producedNode is required");
        }
    }
}
