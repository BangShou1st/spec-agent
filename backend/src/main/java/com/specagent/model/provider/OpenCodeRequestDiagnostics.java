package com.specagent.model.provider;

import com.specagent.common.Hashes;

import java.util.List;

/**
 * Safe request metadata for one OpenCode call.
 *
 * <p>This record contains timing, shape, counts and hashes only. It never
 * contains prompt text, raw request bytes, credentials, or response bodies.</p>
 */
public record OpenCodeRequestDiagnostics(
        String requestType,
        String requestStartedAt,
        Long elapsedMillis,
        Long responseHeadersLatencyMillis,
        Long firstSseEventLatencyMillis,
        int messageCount,
        List<OpenCodeMessageDiagnostics> messages,
        long requestBodyByteCount,
        String requestBodySha256,
        boolean stream,
        boolean temperaturePresent,
        boolean maxTokensPresent,
        boolean responseFormatPresent,
        boolean requestTimeoutPresent,
        boolean connectTimeoutPresent) {

    public OpenCodeRequestDiagnostics {
        requestType = safeText(requestType);
        requestStartedAt = safeText(requestStartedAt);
        elapsedMillis = nonNegative(elapsedMillis);
        responseHeadersLatencyMillis = nonNegative(responseHeadersLatencyMillis);
        firstSseEventLatencyMillis = nonNegative(firstSseEventLatencyMillis);
        messageCount = Math.max(0, messageCount);
        messages = messages == null ? List.of() : List.copyOf(messages);
        requestBodyByteCount = Math.max(0L, requestBodyByteCount);
        requestBodySha256 = requestBodySha256 != null && requestBodySha256.matches("[0-9a-fA-F]{64}")
                ? requestBodySha256 : Hashes.sha256Hex("");
    }

    public static OpenCodeRequestDiagnostics empty() {
        return new OpenCodeRequestDiagnostics(
                "not provided", "not provided", null, null, null, 0, List.of(),
                0, Hashes.sha256Hex(""), false, false, false, false, false, false);
    }

    private static Long nonNegative(Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "not provided";
        }
        String singleLine = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return singleLine.isBlank() ? "not provided" : singleLine;
    }
}
