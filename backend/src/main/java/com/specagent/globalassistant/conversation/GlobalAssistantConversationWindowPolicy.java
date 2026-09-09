package com.specagent.globalassistant.conversation;
/**
 * One deterministic window contract shared by context projection and the
 * rolling summary. Recent history always retains every not-yet-summarized
 * message inside a hard bound, so a pending remainder never disappears
 * while a lagging summary can never grow context without limit.
 */
public final class GlobalAssistantConversationWindowPolicy {
    private GlobalAssistantConversationWindowPolicy() {
    }
    public static final int RECENT_MESSAGES = 24;
    public static final int SUMMARY_CHUNK_SIZE = 10;
    public static final int MAX_RECENT_WITH_REMAINDER = RECENT_MESSAGES + SUMMARY_CHUNK_SIZE - 1;
    public static int summarizedCount(int summaryVersion) {
        return summaryVersion * SUMMARY_CHUNK_SIZE;
    }
    public static int evictedCount(int messageCount) {
        return Math.max(0, messageCount - RECENT_MESSAGES);
    }
    public static int recentStart(int messageCount, int summaryVersion) {
        int normalRecentStart = Math.max(0, messageCount - RECENT_MESSAGES);
        int summarizedStart = summarizedCount(summaryVersion);
        int hardBoundStart = Math.max(0, messageCount - MAX_RECENT_WITH_REMAINDER);
        return Math.max(hardBoundStart, Math.min(normalRecentStart, summarizedStart));
    }
}
