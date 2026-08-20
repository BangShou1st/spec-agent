package com.specagent.model.provider;

import com.specagent.common.Hashes;

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
        int streamedEventCount,
        int reasoningEventCount,
        int reasoningCharCount,
        String reasoningSha256,
        OpenCodeRequestDiagnostics requestDiagnostics) {

    public OpenCodeCompletionResponse(String content,
                                      String finishReason,
                                      Integer promptTokens,
                                      Integer completionTokens,
                                      Integer totalTokens) {
                this(content, finishReason, promptTokens, completionTokens, totalTokens,
                null, 0, 0, 0, Hashes.sha256Hex(""), OpenCodeRequestDiagnostics.empty());
    }

    /** Compatibility constructor for callers that do not observe reasoning metadata. */
    public OpenCodeCompletionResponse(String content,
                                      String finishReason,
                                      Integer promptTokens,
                                      Integer completionTokens,
                Integer totalTokens,
                Integer initialHttpStatus,
                int streamedEventCount) {
        this(content, finishReason, promptTokens, completionTokens, totalTokens,
                initialHttpStatus, streamedEventCount, 0, 0, Hashes.sha256Hex(""),
                OpenCodeRequestDiagnostics.empty());
    }

    /** Compatibility constructor for callers that observe reasoning metadata. */
    public OpenCodeCompletionResponse(String content,
                                      String finishReason,
                                      Integer promptTokens,
                                      Integer completionTokens,
                                      Integer totalTokens,
                                      Integer initialHttpStatus,
                                      int streamedEventCount,
                                      int reasoningEventCount,
                                      int reasoningCharCount,
                                      String reasoningSha256) {
        this(content, finishReason, promptTokens, completionTokens, totalTokens,
                initialHttpStatus, streamedEventCount, reasoningEventCount,
                reasoningCharCount, reasoningSha256, OpenCodeRequestDiagnostics.empty());
    }

    public OpenCodeCompletionResponse {
        content = content == null ? "" : content;
        streamedEventCount = Math.max(0, streamedEventCount);
        reasoningEventCount = Math.max(0, reasoningEventCount);
        reasoningCharCount = Math.max(0, reasoningCharCount);
        reasoningSha256 = reasoningSha256 != null && reasoningSha256.matches("[0-9a-fA-F]{64}")
                ? reasoningSha256 : Hashes.sha256Hex("");
        requestDiagnostics = requestDiagnostics == null
                ? OpenCodeRequestDiagnostics.empty() : requestDiagnostics;
    }
}
