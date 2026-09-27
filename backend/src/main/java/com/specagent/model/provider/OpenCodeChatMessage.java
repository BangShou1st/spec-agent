package com.specagent.model.provider;

/**
 * 文件名:OpenCodeChatMessage.java
 *
 * 用途:OpenCode Zen 最小化补全载荷中的一条聊天消息(role + content)。
 */
public record OpenCodeChatMessage(String role, String content) {

    public OpenCodeChatMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("message role is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("message content is required");
        }
    }
}