package com.specagent.agent.broker;

import java.util.List;
import java.util.UUID;

/**
 * Wire DTO of the internal model inference broker (Python → Spring). Strictly
 * parsed: unknown fields and unknown protocol versions are rejected. The
 * broker never forwards arbitrary headers and never echoes credentials.
 */
public record ModelInferenceHttpRequest(String protocolVersion,
                                        UUID runId,
                                        String callType,
                                        List<Message> messages,
                                        Integer maxOutputTokens) {

    public ModelInferenceHttpRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public record Message(String role, String content) {
    }
}
