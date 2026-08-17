package com.specagent.model.provider;

/**
 * Parsed chat completion result from OpenCode Zen.
 *
 * <p>Only the fields the runtime needs are carried; usage fields are optional
 * because the provider may omit them.
 */
public record OpenCodeCompletionResponse(
        String content,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {

    public OpenCodeCompletionResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("completion content is required");
        }
    }
}