package com.specagent.model.provider;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provider-side OpenCode Zen conversation identities.
 *
 * <p>Transport metadata only: never conversation memory, authorization,
 * WorkingState, a database field or prompt context. One conversation maps to one
 * stable session, so every request inside a project keeps the same provider-side
 * conversation while each HTTP call still gets a fresh {@code msg_} request id
 * (see {@link OpenCodeZenIds}).
 *
 * <p>The header value must have Zen's client shape: {@code ses_} plus 26
 * characters (12-char hex time prefix + 14 base62). A UUID-shaped session is
 * rejected outright with {@code FreeTierError}; that was isolated by
 * single-variable A/B against a live free model, so the run id is never used
 * verbatim as the header value.
 */
public final class OpenCodeZenSessionIds {

    /**
     * Bounded cache so a conversation reuses one provider session instead of
     * presenting a new conversation every call. When the cap is reached the cache
     * is dropped wholesale: sessions are correlation metadata, so a conversation
     * losing its affinity is harmless, while unbounded growth is not.
     */
    private static final int MAX_CACHED_RUNS = 8192;
    private static final ConcurrentMap<UUID, String> RUN_SESSIONS = new ConcurrentHashMap<>();

    private OpenCodeZenSessionIds() {
    }

    /**
     * Stable session for one conversation. The conversation is the owning project
     * when the caller resolved one, so every request inside a project reuses the
     * same provider-side conversation; a null identity fails closed.
     */
    public static String forConversation(UUID conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Zen session conversation id must not be null");
        }
        String cached = RUN_SESSIONS.get(conversationId);
        if (cached != null) {
            return cached;
        }
        if (RUN_SESSIONS.size() >= MAX_CACHED_RUNS) {
            RUN_SESSIONS.clear();
        }
        String generated = OpenCodeZenIds.sessionId();
        String existing = RUN_SESSIONS.putIfAbsent(conversationId, generated);
        return existing == null ? generated : existing;
    }

    /**
     * Single-use session for run-less requests (credential probes, model
     * discovery).
     */
    public static String newEphemeral() {
        return OpenCodeZenIds.sessionId();
    }
}
