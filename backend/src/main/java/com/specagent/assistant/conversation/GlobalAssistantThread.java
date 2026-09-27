package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantThread.java
 *
 * 用途:一个全局助手会话线程(不可变 record),持有有界的工作状态
 * (working state)与滚动摘要,两者都带版本号以支持 CAS 乐观并发更新。
 *
 * 角色:conversation 包的线程领域模型,是消息、Run 与事件的归属根。
 */
public record GlobalAssistantThread(
        UUID id,
        String summary,
        int summaryVersion,
        String workingStateJson,
        int workingStateVersion,
        Instant createdAt,
        Instant updatedAt) {
}
