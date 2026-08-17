package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;

/**
 * Outcome of one fake agent run.
 *
 * <p>The produced node is null on failure paths but must be non-null whenever
 * the run completes successfully.
 */
public record FakeAgentRunResult(
        AgentRun run,
        ContextSnapshot contextSnapshot,
        ModelResponse modelResponse,
        Node producedNode
) {
    public FakeAgentRunResult {
        if (run == null) {
            throw new IllegalArgumentException("run is required");
        }
        if (contextSnapshot == null) {
            throw new IllegalArgumentException("contextSnapshot is required");
        }
        if (modelResponse == null) {
            throw new IllegalArgumentException("modelResponse is required");
        }
    }
}