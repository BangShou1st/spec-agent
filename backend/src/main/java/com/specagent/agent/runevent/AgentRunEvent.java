package com.specagent.agent.runevent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentRunEvent.java
 *
 * 用途:一条只追加(append-only)的运行事件——与某个运行阶段绑定的、
 * 已脱敏的 trace/进度记录。
 *
 * 约束:payload 只承载哈希、计数与分类——绝不包含 prompt 原文、
 * provider 载荷或隐藏的思维链(chain-of-thought)。
 */
public record AgentRunEvent(UUID id,
                            UUID runId,
                            int sequence,
                            AgentRunPhase phase,
                            String eventType,
                            Map<String, Object> payload,
                            Instant createdAt) {
}
