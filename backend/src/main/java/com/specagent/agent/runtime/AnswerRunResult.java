package com.specagent.agent.runtime;

import com.specagent.agent.decision.ModelResponse;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.patch.AnswerPatch;

/** Production-neutral outcome of a successful answer-processing run. */
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
