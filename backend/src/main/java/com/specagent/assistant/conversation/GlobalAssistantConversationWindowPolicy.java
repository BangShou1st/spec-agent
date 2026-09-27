package com.specagent.assistant.conversation;
/**
 * 文件名:GlobalAssistantConversationWindowPolicy.java
 *
 * 用途:定义上下文投影与滚动摘要共用的唯一确定性"窗口契约":
 * 最近多少条消息保留在上下文里、多少条被摘要吞并、窗口从哪里开始。
 *
 * 角色:纯静态策略常量与计算函数。核心不变式:近期窗口在硬上限内
 * 始终保留所有尚未被摘要覆盖的消息——既保证待摘要的剩余消息不会凭空
 * 消失,也保证滞后的摘要永远不会让上下文无限增长。
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
