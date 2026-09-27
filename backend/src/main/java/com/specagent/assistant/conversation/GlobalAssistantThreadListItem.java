package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantThreadListItem.java
 *
 * 用途:会话列表(Conversation Library)的读模型条目,一个线程的
 * 确定性投影:标题取首条 USER 消息,预览取最新一条规范消息,
 * updatedAt 取该消息的 created_at。
 *
 * 角色:conversation 包的列表展示模型,不引入 AI、不加新列,
 * 全部由既有消息数据推导。
 */
public record GlobalAssistantThreadListItem(
        UUID threadId,
        String title,
        String preview,
        Instant updatedAt,
        Instant createdAt) {
}
