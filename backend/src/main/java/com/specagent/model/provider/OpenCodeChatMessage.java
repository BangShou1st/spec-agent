package com.specagent.model.provider;

/**
 * One chat message in the minimal OpenCode Zen completion payload.
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