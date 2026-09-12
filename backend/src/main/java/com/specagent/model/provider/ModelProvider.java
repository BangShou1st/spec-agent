package com.specagent.model.provider;

/**
 * Provider identity for MODEL PROVIDERS V1 final design.
 *
 * <p>Three identities only. Custom API format is NOT a provider; it is
 * carried by {@link CustomApiFormat} and routed inside the Custom boundary.
 */
public enum ModelProvider {
    OPENCODE_ZEN,
    OPENROUTER,
    CUSTOM;

    public static ModelProvider fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("model provider is required");
        }
        return switch (code.trim().toUpperCase()) {
            case "OPENCODE_ZEN" -> OPENCODE_ZEN;
            case "OPENROUTER" -> OPENROUTER;
            case "CUSTOM" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown model provider: " + code);
        };
    }
}
