package com.specagent.runtime.execution;

import java.util.UUID;

/** Neutral boundary: validate execution ownership, persisted delegation and live Run before business locks. */
public interface WorkspaceRunExecutionAccessPort {
    void requireExecution(UUID projectId, UUID runId);
}
