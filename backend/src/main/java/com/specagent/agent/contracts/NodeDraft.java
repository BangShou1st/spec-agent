package com.specagent.agent.contracts;

import com.specagent.node.NodeOption;

import java.util.List;

/**
 * Draft of a clarification node proposed by the agent loop.
 */
public record NodeDraft(
        String question,
        String purpose,
        List<NodeOption> options,
        boolean allowFreeAnswer
) {
    public NodeDraft {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        purpose = purpose == null ? "" : purpose;
        options = options == null ? List.of() : List.copyOf(options);
    }
}