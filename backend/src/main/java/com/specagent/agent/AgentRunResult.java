package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;

/** Production-neutral outcome of a successful node-drafting run. */
public record AgentRunResult(AgentRun run, ContextSnapshot contextSnapshot,
                             ModelResponse modelResponse, Node producedNode) {
    public AgentRunResult {
        if (run == null || contextSnapshot == null || modelResponse == null || producedNode == null) {
            throw new IllegalArgumentException("agent run result fields are required");
        }
    }
}
