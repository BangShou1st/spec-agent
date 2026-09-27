package com.specagent.assistant.conversation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:GlobalAssistantMessage.java
 *
 * 用途:面向用户的会话消息(不可变 record),对应持久化表中的
 * 一条 USER/ASSISTANT 消息,带线程、Run 归属与序号。
 *
 * 角色:conversation 包的消息领域模型。持久化只保留 USER 与
 * ASSISTANT 两种角色;THOUGHT/REASONING/TOOL/SYSTEM 等中间产物一律
 * 不落在这里,工具执行细节由能力调用记录(capability invocations)
 * 和 Run 事件承载。
 */
public record GlobalAssistantMessage(
        UUID id,
        UUID threadId,
        Role role,
        String content,
        UUID runId,
        Instant createdAt,
        long sequence,
        String providerLabel,
        String modelId) {
    public enum Role {
        USER,
        ASSISTANT;
        public static Role fromCode(String code) {
            if (code == null) {
                throw new IllegalArgumentException("Message role must not be null");
            }
            return valueOf(code.trim().toUpperCase());
        }
    }
}
