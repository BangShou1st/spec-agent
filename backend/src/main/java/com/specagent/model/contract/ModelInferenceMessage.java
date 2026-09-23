package com.specagent.model.contract;

/**
 * One provider-neutral chat message approved by the runtime.
 */
public record ModelInferenceMessage(String role, String content) {
}
