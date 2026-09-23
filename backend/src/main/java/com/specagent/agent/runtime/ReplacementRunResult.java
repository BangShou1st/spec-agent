package com.specagent.agent.runtime;

import com.specagent.agent.decision.ModelResponse;

import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.route.RegenerateResult;

/** Result of an accepted model-powered replacement proposal. */
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
