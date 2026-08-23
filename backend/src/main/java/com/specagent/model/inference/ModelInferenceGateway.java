package com.specagent.model.inference;

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
}
