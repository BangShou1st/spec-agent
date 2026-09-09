package com.specagent.globalassistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * User-facing conversation message only. Persisted roles are USER|ASSISTANT.
 * No THOUGHT/REASONING/TOOL/SYSTEM roles are persisted here; tool detail
 * lives in capability invocations + run events.
 */
public record GlobalAssistantMessage(
        UUID id,
        UUID threadId,
        Role role,
        String content,
        UUID runId,
        Instant createdAt) {
    public enum Role {
        USER,
        ASSISTANT;
        public static Role fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("Message role must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
}
