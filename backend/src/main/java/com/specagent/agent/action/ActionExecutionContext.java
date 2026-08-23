package com.specagent.agent.action;

import java.util.UUID;

/**
 * Runtime context for executing or validating an action proposal. Carries
 * the durable identity and current graph state needed for liveness checks
 * and action application.
 */
public record ActionExecutionContext(UUID runId,
                                     UUID projectId,
                                     UUID routeId,
                                     UUID contextSnapshotId,
                                     UUID anchorNodeId,
                                     UUID selectedOptionId,
                                     String freeText) {
}
