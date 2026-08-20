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
        Integer totalTokens,
        Integer initialHttpStatus,
        int streamedEventCount) {

    public OpenCodeCompletionResponse(String content,
                                      String finishReason,
                                      Integer promptTokens,
                                      Integer completionTokens,
                                      Integer totalTokens) {
        this(content, finishReason, promptTokens, completionTokens, totalTokens, null, 0);
    }

    public OpenCodeCompletionResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("completion content is required");
        }
        streamedEventCount = Math.max(0, streamedEventCount);
    }
}
