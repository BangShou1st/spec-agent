package com.specagent.model.provider;

import com.specagent.common.Hashes;

import java.util.List;

/**
 * Bounded, allowlisted diagnostics for one OpenCode failure.
 *
 * <p>This record intentionally contains metadata and hashes only. It never
 * carries a request prompt, Authorization value, raw SSE event, or complete
 * model output.</p>
 */
public record OpenCodeFailureDiagnostics(
        String task,
        String selectedModel,
        String endpointPath,
        Integer initialHttpStatus,
        OpenCodeDiagnosticReason diagnosticReason,
        String finishReason,
        int streamedEventCount,
        int contentCharCount,
        String contentSha256,
        Integer eventIndex,
        List<String> topLevelFields,
        Integer choicesCount,
        List<String> deltaFields,
        String providerType,
        String providerCode,
        String providerMessage,
        String retryAfter,
        String xRequestId,
        String requestId,
        String cfRay,
        String traceId,
        int reasoningEventCount,
        int reasoningCharCount,
        String reasoningSha256) {

    /** Compatibility constructor for diagnostics without reasoning metadata. */
    public OpenCodeFailureDiagnostics(
            String task,
            String selectedModel,
            String endpointPath,
            Integer initialHttpStatus,
            OpenCodeDiagnosticReason diagnosticReason,
            String finishReason,
            int streamedEventCount,
            int contentCharCount,
            String contentSha256,
            Integer eventIndex,
            List<String> topLevelFields,
            Integer choicesCount,
            List<String> deltaFields,
            String providerType,
            String providerCode,
            String providerMessage,
            String retryAfter,
            String xRequestId,
            String requestId,
            String cfRay,
            String traceId) {
        this(task, selectedModel, endpointPath, initialHttpStatus, diagnosticReason,
                finishReason, streamedEventCount, contentCharCount, contentSha256,
                eventIndex, topLevelFields, choicesCount, deltaFields, providerType,
                providerCode, providerMessage, retryAfter, xRequestId, requestId,
                cfRay, traceId, 0, 0, Hashes.sha256Hex(""));
    }

    public OpenCodeFailureDiagnostics {
        task = safeText(task);
        selectedModel = safeText(selectedModel);
        endpointPath = safeText(endpointPath);
        finishReason = safeText(finishReason);
        streamedEventCount = Math.max(0, streamedEventCount);
        contentCharCount = Math.max(0, contentCharCount);
        contentSha256 = safeHash(contentSha256);
        reasoningEventCount = Math.max(0, reasoningEventCount);
        reasoningCharCount = Math.max(0, reasoningCharCount);
        reasoningSha256 = safeHash(reasoningSha256);
        topLevelFields = safeFieldNames(topLevelFields);
        deltaFields = safeFieldNames(deltaFields);
        providerType = safeText(providerType);
        providerCode = safeText(providerCode);
        providerMessage = safeText(providerMessage);
        retryAfter = safeText(retryAfter);
        xRequestId = safeText(xRequestId);
        requestId = safeText(requestId);
        cfRay = safeText(cfRay);
        traceId = safeText(traceId);
    }

    public static OpenCodeFailureDiagnostics empty() {
        return new OpenCodeFailureDiagnostics(
                "not provided", "not provided", "not provided", null, null,
                "not provided", 0, 0, Hashes.sha256Hex(""), null, List.of(), null,
                List.of(), "not provided", "not provided", "not provided",
                "not provided", "not provided", "not provided", "not provided",
                "not provided");
    }

    public OpenCodeFailureDiagnostics withTask(String value) {
        return copy(value, selectedModel, endpointPath, initialHttpStatus, diagnosticReason,
                finishReason, streamedEventCount, contentCharCount, contentSha256, eventIndex,
                topLevelFields, choicesCount, deltaFields, providerType, providerCode,
                providerMessage, retryAfter, xRequestId, requestId, cfRay, traceId,
                reasoningEventCount, reasoningCharCount, reasoningSha256);
    }

    public OpenCodeFailureDiagnostics withSelectedModel(String value) {
        return copy(task, value, endpointPath, initialHttpStatus, diagnosticReason,
                finishReason, streamedEventCount, contentCharCount, contentSha256, eventIndex,
                topLevelFields, choicesCount, deltaFields, providerType, providerCode,
                providerMessage, retryAfter, xRequestId, requestId, cfRay, traceId,
                reasoningEventCount, reasoningCharCount, reasoningSha256);
    }

    public static OpenCodeFailureDiagnostics httpFailure(
            String selectedModel,
            String endpointPath,
            int initialHttpStatus,
            String providerType,
            String providerCode,
            String providerMessage,
            String retryAfter,
            String xRequestId,
            String requestId,
            String cfRay,
            String traceId) {
        return new OpenCodeFailureDiagnostics(
                "not provided", selectedModel, endpointPath, initialHttpStatus, null,
                "not provided", 0, 0, Hashes.sha256Hex(""), null, List.of(), null, List.of(),
                providerType, providerCode, providerMessage, retryAfter, xRequestId,
                requestId, cfRay, traceId);
    }

    private OpenCodeFailureDiagnostics copy(
            String nextTask,
            String nextModel,
            String nextPath,
            Integer nextStatus,
            OpenCodeDiagnosticReason nextReason,
            String nextFinishReason,
            int nextEventCount,
            int nextContentChars,
            String nextContentHash,
            Integer nextEventIndex,
            List<String> nextTopLevelFields,
            Integer nextChoicesCount,
            List<String> nextDeltaFields,
            String nextProviderType,
            String nextProviderCode,
            String nextProviderMessage,
            String nextRetryAfter,
            String nextXRequestId,
            String nextRequestId,
            String nextCfRay,
            String nextTraceId,
            int nextReasoningEventCount,
            int nextReasoningCharCount,
            String nextReasoningSha256) {
        return new OpenCodeFailureDiagnostics(
                nextTask, nextModel, nextPath, nextStatus, nextReason, nextFinishReason,
                nextEventCount, nextContentChars, nextContentHash, nextEventIndex,
                nextTopLevelFields, nextChoicesCount, nextDeltaFields, nextProviderType,
                nextProviderCode, nextProviderMessage, nextRetryAfter, nextXRequestId,
                nextRequestId, nextCfRay, nextTraceId, nextReasoningEventCount,
                nextReasoningCharCount, nextReasoningSha256);
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "not provided";
        }
        String singleLine = value.replaceAll("[\\r\\n\\t]", " ").trim()
                .replaceAll("(?i)\\bBearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9._-]+", "sk-<redacted>");
        if (singleLine.isBlank()) {
            return "not provided";
        }
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
    }

    private static String safeHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}")
                ? value : Hashes.sha256Hex("");
    }

    private static List<String> safeFieldNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .limit(32)
                .map(value -> value == null ? "not_provided"
                        : value.replaceAll("[^A-Za-z0-9_.-]", "_"))
                .toList();
    }
}
