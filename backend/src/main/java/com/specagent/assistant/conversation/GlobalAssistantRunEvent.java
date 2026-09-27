package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantRunEvent.java
 *
 * 用途:一条已持久化的公共 Run 事件(不可变 record),含 Run 归属、
 * 每 Run 单调递增的序号、事件类型与负载;序号同时就是 SSE 的 id。
 *
 * 角色:conversation 包的事件领域模型,支撑事件回放与断线重连
 * (Last-Event-ID)续传。负载做了防御性拷贝,null 负载统一为空 Map。
 */
public record GlobalAssistantRunEvent(
        UUID id,
        UUID runId,
        int sequence,
        String type,
        Map<String, Object> payload,
        Instant createdAt) {
    public GlobalAssistantRunEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
