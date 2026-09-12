package com.specagent.model.inference;

import com.specagent.model.provider.FragmentListener;

/**
 * The lower-level provider-neutral inference seam shared by all model callers.
 *
 * <p>The legacy {@code ModelGateway} renders task prompts and parses the model
 * envelope; this seam sits below it and carries runtime-approved messages
 * only. The internal inference broker (serving the Python brain) and any
 * future Java-side caller share this port, so provider transport is never
 * duplicated across language boundaries.
 *
 * <p>Implementations resolve credentials internally and never expose them.
 * No retry and no provider fallback may be added behind this port.
 */
public interface ModelInferenceGateway {

    ModelInferenceResponse complete(ModelInferenceRequest request);

    /**
     * Streaming variant of {@link #complete(ModelInferenceRequest)}. Provider
     * content fragments reach {@code listener} in arrival order while the
     * provider is still generating; the returned response is the same fully
     * aggregated contract as {@code complete}. Control plane is unchanged:
     * callers must still validate the complete response before acting on it.
     *
     * @throws com.specagent.model.provider.StreamCancelledException when the listener declines content
     */
    default ModelInferenceResponse completeStreaming(ModelInferenceRequest request,
            FragmentListener listener) {
        ModelInferenceResponse response = complete(request);
        if (response != null && response.content() != null && !response.content().isEmpty()) {
            if (!listener.onFragment(response.content())) {
                throw new com.specagent.model.provider.StreamCancelledException("listener declined content");
            }
        }
        return response;
    }
}
