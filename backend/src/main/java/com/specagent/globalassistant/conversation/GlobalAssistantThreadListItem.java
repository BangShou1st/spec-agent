package com.specagent.globalassistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation Library read-model: deterministic projection of one thread.
 * Title = first USER message, preview = latest canonical message,
 * updatedAt = latest canonical message created_at. No AI, no new columns.
 */
public record GlobalAssistantThreadListItem(
        UUID threadId,
        String title,
        String preview,
        Instant updatedAt,
        Instant createdAt) {
}
