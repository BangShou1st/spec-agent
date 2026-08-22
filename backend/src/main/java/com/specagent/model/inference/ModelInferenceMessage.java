package com.specagent.model.inference;

/**
 * One provider-neutral chat message approved by the runtime.
 */
public record ModelInferenceMessage(String role, String content) {
}
