package com.specagent.agent.broker;

/**
 * Wire response of the internal model inference broker. Carries the
 * completion content plus sanitized accounting only — the provider key never
 * appears here or in any log line.
 */
public record ModelInferenceHttpResponse(String protocolVersion,
                                         String content,
                                         String finishReason,
                                         Usage usage) {

    public record Usage(Integer promptTokens, Integer completionTokens) {
    }
}
