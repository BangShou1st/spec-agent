package com.specagent.model.provider;

import java.util.List;
import java.util.Map;

/**
 * Minimal chat completion payload for OpenCode Zen.
 *
 * <p>Production completion requests use the OpenAI-compatible streaming shape
 * required by the verified OpenCode client. The transport owns the wire-only
 * fields; this DTO carries only the model, the messages and the optional
 * provider-native format map translated from the neutral output contract.
 * A null format keeps the historical text shape byte-identical. Production
 * task types do not carry a task-specific generation limit.
 */
public record OpenCodeChatCompletionRequest(
        String model,
        List<OpenCodeChatMessage> messages,
        Map<String, Object> responseFormat) {

    public OpenCodeChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        responseFormat = responseFormat == null || responseFormat.isEmpty()
                ? null
                : Map.copyOf(responseFormat);
    }

    /**
     * Historical shape: no provider format enforcement.
     */
    public OpenCodeChatCompletionRequest(String model, List<OpenCodeChatMessage> messages) {
        this(model, messages, null);
    }
}
