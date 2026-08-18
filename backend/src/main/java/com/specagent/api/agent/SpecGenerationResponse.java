package com.specagent.api.agent;

import com.specagent.api.spec.SpecSnapshotResponse;

/**
 * Result of a spec generation command: the completed run and the derived spec
 * snapshot. The snapshot remains a derived artifact, never source of truth.
 */
public record SpecGenerationResponse(
        AgentRunResponse agentRun,
        SpecSnapshotResponse specSnapshot) {
}