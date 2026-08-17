package com.specagent.model.provider;

import java.util.List;

/**
 * Minimal chat completion payload for OpenCode Zen.
 *
 * <p>Phase 5.1 keeps the payload minimal: model, messages, a JSON object
 * response format, no streaming, fixed temperature and token cap. Per-task
 * prompt engineering is Phase 5.2.
 */
public record OpenCodeChatCompletionRequest(
        String model,
        List<OpenCodeChatMessage> messages,
        double temperature,
        int maxTokens) {

    public OpenCodeChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }
}