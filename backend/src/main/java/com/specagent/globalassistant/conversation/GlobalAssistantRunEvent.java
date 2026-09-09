package com.specagent.globalassistant.conversation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One persisted public run event. SSE id = per-run sequence.
 */
public record GlobalAssistantRunEvent(
        UUID id,
        UUID runId,
        int sequence,
        String type,
        Map<String, Object> payload,
        Instant createdAt) {
    public GlobalAssistantRunEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
