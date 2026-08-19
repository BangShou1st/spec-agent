package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.route.RegenerateResult;

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
