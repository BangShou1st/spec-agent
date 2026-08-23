package com.specagent.agent.broker;

import java.util.UUID;

/**
 * Port interface for verifying that a run ID exists before the internal
 * inference broker processes a request. The implementation lives in the
 * runtime package and delegates to durable persistence, keeping the broker
 * free of repository dependencies.
 */
@FunctionalInterface
public interface RunExistenceCheck {

    /**
     * Returns {@code true} when the given run ID corresponds to a real,
     * persisted AgentRun record.
     */
    boolean exists(UUID runId);
}
