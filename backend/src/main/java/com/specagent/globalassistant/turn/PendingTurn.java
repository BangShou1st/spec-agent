package com.specagent.globalassistant.turn;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable pending steer. Handoff persistence only; becomes conversation
 * history exactly once as a canonical USER message on successor creation.
 */
public record PendingTurn(
        UUID id,
        UUID threadId,
        UUID interruptedRunId,
        String message,
        String uiContextJson,
        PendingTurnStatus status,
        UUID successorRunId,
        Instant createdAt,
        Instant claimedAt,
        Instant consumedAt,
        Instant discardedAt) {
}
