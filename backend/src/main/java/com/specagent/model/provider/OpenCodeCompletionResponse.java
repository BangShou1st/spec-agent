package com.specagent.model.provider;

import com.specagent.common.Hashes;

/**
 * 文件名:OpenCodeCompletionResponse.java
 *
 * 用途:OpenCode Zen chat completion 的解析结果。只携带运行时需要的字段;
 * 用量字段是可选的,因为提供商可能省略它们。
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

    /** 兼容构造器:供不观察 reasoning 元数据的调用方使用。 */
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

    /** 兼容构造器:供观察 reasoning 元数据的调用方使用。 */
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
