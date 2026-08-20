package com.specagent.model.provider;

import java.util.List;

/**
 * Minimal chat completion payload for OpenCode Zen.
 *
 * <p>Production completion requests use the OpenAI-compatible streaming shape
 * required by the verified OpenCode client. The transport owns the wire-only
 * fields; this DTO carries only the model and messages. Production
 * task types do not carry a task-specific generation limit.
 */
public record OpenCodeChatCompletionRequest(
        String model,
        List<OpenCodeChatMessage> messages) {

    public OpenCodeChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
    }
}
