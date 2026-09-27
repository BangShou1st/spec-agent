package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantRun.java
 *
 * 用途:线程内一次有界的全局助手执行(不可变 record),记录 Run 的
 * 状态、步数、取消请求时间、起止时间,以及本次执行所用的
 * prompt 版本、上下文投影版本与工具目录指纹,失败时附带错误码。
 *
 * 角色:conversation 包的 Run 领域模型,一次用户提问对应一个 Run,
 * 由 runtime 层驱动其状态流转。
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
