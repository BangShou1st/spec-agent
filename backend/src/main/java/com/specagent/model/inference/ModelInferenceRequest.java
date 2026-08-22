package com.specagent.model.inference;

import java.util.List;
import java.util.UUID;

/**
 * Provider-neutral lower-level inference request.
 *
 * <p>Carries runtime-approved model messages rather than an {@code AgentTaskType}:
 * the caller (the internal inference broker on behalf of the Python brain)
 * already owns prompt construction and orchestration. {@code runId} ties every
 * call to one durable agent run; {@code callType} is the sanitized call class
 * recorded in run events.
 */
public record ModelInferenceRequest(UUID runId,
                                    String callType,
                                    List<ModelInferenceMessage> messages,
                                    Integer maxOutputTokens) {

    public ModelInferenceRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
