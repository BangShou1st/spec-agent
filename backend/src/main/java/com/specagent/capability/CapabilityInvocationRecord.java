package com.specagent.capability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:CapabilityInvocationRecord.java
 *
 * 用途:能力调用日志的一行持久化记录,用于审计与幂等重放查询。
 * 运行时(而非模型)持有这里记录的重试/幂等元数据。
 *
 * @param id            记录 ID
 * @param invocationKey 幂等键
 * @param projectId     所属项目 ID
 * @param runId         所属运行 ID
 * @param capabilityId  执行的能力标识
 * @param arguments     调用参数
 * @param status        调用状态
 * @param result        结果内容
 * @param createdAt     创建时间
 * @param completedAt   完成时间
 */
public record CapabilityInvocationRecord(
        UUID id,
        String invocationKey,
        UUID projectId,
        UUID runId,
        String capabilityId,
        Map<String, Object> arguments,
        CapabilityResult.Status status,
        Map<String, Object> result,
        Instant createdAt,
        Instant completedAt) {
}
