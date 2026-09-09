package com.specagent.globalassistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * One Global Assistant conversation thread.
 * Owns bounded working state + rolling summary (both versioned for CAS).
 */
public record GlobalAssistantThread(
        UUID id,
        String summary,
        int summaryVersion,
        String workingStateJson,
        int workingStateVersion,
        Instant createdAt,
        Instant updatedAt) {
}
