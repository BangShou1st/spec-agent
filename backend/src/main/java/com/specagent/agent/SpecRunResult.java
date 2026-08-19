package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.spec.SpecSnapshot;

/** Production-neutral outcome of a successful spec generation run. */
public record SpecRunResult(AgentRun run, ContextSnapshot contextSnapshot,
                            ModelResponse modelResponse, SpecSnapshot specSnapshot) {
    public SpecRunResult {
        if (run == null || contextSnapshot == null || modelResponse == null || specSnapshot == null) {
            throw new IllegalArgumentException("spec run result fields are required");
        }
    }
}
