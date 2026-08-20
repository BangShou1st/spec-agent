package com.specagent.model.provider;

import java.util.List;

/**
 * Minimal chat completion payload for OpenCode Zen.
 *
 * <p>Production completion requests use the OpenAI-compatible streaming shape
 * required by the verified OpenCode client. The transport owns the wire-only
 * fields; this DTO carries the model, messages, temperature and bounded task
 * budget without coupling the agent contracts to a provider response format.
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
