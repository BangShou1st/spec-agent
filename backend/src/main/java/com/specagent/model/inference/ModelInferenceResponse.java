package com.specagent.model.inference;

/**
 * Provider-neutral lower-level inference result. Carries the completion
 * content plus sanitized accounting only — never credentials, raw provider
 * payloads, or request headers.
 */
public record ModelInferenceResponse(String content,
                                     String finishReason,
                                     Integer promptTokens,
                                     Integer completionTokens) {
}
