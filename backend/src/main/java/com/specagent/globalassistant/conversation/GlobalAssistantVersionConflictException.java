package com.specagent.globalassistant.conversation;
/**
 * Optimistic-concurrency conflict on versioned thread state. Retrying with a
 * fresh read is safe; this is distinct from corrupt durable state, which
 * must fail closed instead.
 */
public class GlobalAssistantVersionConflictException extends IllegalStateException {
    public GlobalAssistantVersionConflictException(String message) {
        super(message);
    }
}
