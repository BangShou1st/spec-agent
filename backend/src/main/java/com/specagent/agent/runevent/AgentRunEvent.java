package com.specagent.agent.runevent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only run event: a sanitized trace/progress record tied to a run
 * phase. Payloads carry hashes, counts and categories — never prompt text,
 * provider payloads, or hidden chain-of-thought.
 */
public record AgentRunEvent(UUID id,
                            UUID runId,
                            int sequence,
                            AgentRunPhase phase,
                            String eventType,
                            Map<String, Object> payload,
                            Instant createdAt) {
}
