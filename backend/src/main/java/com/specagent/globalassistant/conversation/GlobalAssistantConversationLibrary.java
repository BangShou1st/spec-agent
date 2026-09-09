package com.specagent.globalassistant.conversation;

/**
 * Deterministic read projection for Conversation Library.
 * Zero model cost, provider-independent. Code-point aware truncation.
 */
public final class GlobalAssistantConversationLibrary {
    public static final int TITLE_MAX = 48;
    public static final int PREVIEW_MAX = 96;
    public static final int LIST_LIMIT = 50;

    private GlobalAssistantConversationLibrary() {
    }

    public static String toTitle(String raw) {
        return normalizeAndTruncate(raw, TITLE_MAX);
    }

    public static String toPreview(String raw) {
        return normalizeAndTruncate(raw, PREVIEW_MAX);
    }

    static String normalizeAndTruncate(String raw, int maxInclusive) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return "";
        }
        int count = collapsed.codePointCount(0, collapsed.length());
        if (count <= maxInclusive) {
            return collapsed;
        }
        int endIndex = collapsed.offsetByCodePoints(0, maxInclusive - 1);
        return collapsed.substring(0, endIndex) + "…";
    }
}
