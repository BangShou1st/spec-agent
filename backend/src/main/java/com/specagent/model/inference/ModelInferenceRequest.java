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
 *
 * <p>{@code conversationId} is the owning project when the caller can resolve it.
 * Providers that keep server-side conversation state identify it per project, so
 * every request of one project presents the same conversation; request-level
 * identifiers stay per call. It is optional: a caller that knows no project
 * (compatibility probes, project-less assistant turns) leaves it {@code null} and
 * the conversation falls back to the run.
 */
public record ModelInferenceRequest(UUID runId,
                                    String callType,
                                    List<ModelInferenceMessage> messages,
                                    Integer maxOutputTokens,
                                    ModelOutputContract outputContract,
                                    UUID conversationId) {

    public ModelInferenceRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        outputContract = outputContract == null ? ModelOutputContract.text() : outputContract;
    }

    /**
     * Historical shape: behaves as {@link ModelOutputContract.Text} with no
     * project affinity. Existing callers keep their exact behavior.
     */
    public ModelInferenceRequest(UUID runId,
                                 String callType,
                                 List<ModelInferenceMessage> messages,
                                 Integer maxOutputTokens) {
        this(runId, callType, messages, maxOutputTokens, ModelOutputContract.text(), null);
    }

    /**
     * Historical shape: no project affinity, so the conversation falls back to
     * the run.
     */
    public ModelInferenceRequest(UUID runId,
                                 String callType,
                                 List<ModelInferenceMessage> messages,
                                 Integer maxOutputTokens,
                                 ModelOutputContract outputContract) {
        this(runId, callType, messages, maxOutputTokens, outputContract, null);
    }

    /**
     * The identity a provider should treat as one conversation: the owning
     * project when known, otherwise the run.
     */
    public UUID conversationOrRun() {
        return conversationId == null ? runId : conversationId;
    }
}
