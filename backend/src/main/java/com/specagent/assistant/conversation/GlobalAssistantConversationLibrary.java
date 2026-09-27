package com.specagent.assistant.conversation;

/**
 * 文件名:GlobalAssistantConversationLibrary.java
 *
 * 用途:会话列表(Conversation Library)的确定性只读投影工具:
 * 把原始消息文本规整为线程标题与列表预览。
 *
 * 角色:纯静态工具类,零模型开销、不依赖任何模型提供方,保证同样的
 * 输入永远得到同样的输出。截断按 Unicode 码点(code point)计数,
 * 避免把增补平面字符(如 emoji)截成半截。
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
