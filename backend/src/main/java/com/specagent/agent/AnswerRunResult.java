package com.specagent.agent;

import com.specagent.answer.Answer;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.patch.AnswerPatch;

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
