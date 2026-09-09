package com.specagent.globalassistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * One bounded Global Assistant execution within a thread.
 */
public record GlobalAssistantRun(
        UUID id,
        UUID threadId,
        GlobalAssistantRunStatus status,
        int stepCount,
        Instant cancelRequestedAt,
        Instant startedAt,
        Instant completedAt,
        String promptVersion,
        String contextProjectionVersion,
        String toolCatalogFingerprint,
        String errorCode) {
}
