package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * Result of one answer cycle. Carries the durable artifacts produced
 * during the 2-call convergence path so callers can assert and display
 * outcomes.
 */
public record AnswerCycleResult(UUID runId,
                                UUID answerId,
                                UUID patchId,
                                UUID producedNodeId,
                                String status) {
}
