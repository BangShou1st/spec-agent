package com.specagent.model.provider;

/**
 * Safe internal reasons for an OpenCode response that cannot be consumed.
 *
 * <p>The reason is diagnostic metadata only. The public API deliberately keeps
 * returning the stable provider-neutral INVALID_RESPONSE category.</p>
 */
public enum OpenCodeDiagnosticReason {
    CONNECT_TIMEOUT,
    RESPONSE_TIMEOUT,
    STREAM_ERROR_EVENT,
    STREAM_MALFORMED_JSON,
    STREAM_MISSING_CHOICES,
    STREAM_MISSING_DELTA,
    STREAM_NON_TEXT_CONTENT,
    MODEL_OUTPUT_NOT_JSON,
    MODEL_OUTPUT_MISSING_ACTION,
    MODEL_OUTPUT_MISSING_OUTPUT,
    MODEL_OUTPUT_UNKNOWN_ACTION,
    MODEL_OUTPUT_TRUNCATED
}
