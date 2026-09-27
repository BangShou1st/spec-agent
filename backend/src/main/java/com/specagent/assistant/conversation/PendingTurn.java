package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:PendingTurn.java
 *
 * 用途:一条可持久化的待处理 Steer 轮次(用户在 Run 运行中插话的
 * 交接记录),记录被打断的 Run、插话内容、UI 上下文与后继 Run。
 *
 * 角色:conversation 包的 Steer 交接持久化模型,只做存续,不做事。
 * 在后继 Run 创建时,它会恰好一次地转化为规范 USER 消息进入会话历史。
 */
public record PendingTurn(
        UUID id,
        UUID threadId,
        UUID interruptedRunId,
        String message,
        String uiContextJson,
        PendingTurnStatus status,
        UUID successorRunId,
        Instant createdAt,
        Instant claimedAt,
        Instant consumedAt,
        Instant discardedAt) {
}
