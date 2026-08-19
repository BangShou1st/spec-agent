package com.specagent.agent;

import com.specagent.context.ContextSnapshot;
import com.specagent.spec.SpecSnapshot;

/**
 * Outcome of one fake spec generation run.
 *
 * <p>Every field is required and non-null whenever the run completes
 * successfully. The spec snapshot is a derived artifact tied to the frozen
 * context snapshot of this run; it is never source of truth.
 */
public record FakeSpecRunResult(
        AgentRun run,
        ContextSnapshot contextSnapshot,
        ModelResponse modelResponse,
        SpecSnapshot specSnapshot
) {
    public FakeSpecRunResult {
        if (run == null) {
            throw new IllegalArgumentException("run is required");
        }
        if (contextSnapshot == null) {
            throw new IllegalArgumentException("contextSnapshot is required");
        }
        if (modelResponse == null) {
            throw new IllegalArgumentException("modelResponse is required");
        }
        if (specSnapshot == null) {
            throw new IllegalArgumentException("specSnapshot is required");
        }
    }
}
