package com.specagent.agent;

import com.specagent.agent.decision.ModelResponse;
import com.specagent.agent.runtime.AgentRun;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.patch.AnswerPatch;

/**
 * Outcome of one fake answer run: the immutable answer, the grounded answer
 * patch, and the next node drafted after the answer.
 *
 * <p>Every field is required and non-null whenever the run completes
 * successfully. The three model responses correspond to the three model calls
 * of the loop: interpret the answer, draft the answer patch, draft the next
 * node.
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
