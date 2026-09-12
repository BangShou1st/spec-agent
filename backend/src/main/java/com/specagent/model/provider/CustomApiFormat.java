package com.specagent.model.provider;

/**
 * Custom provider wire protocol. Provider identity stays {@link ModelProvider#CUSTOM};
 * this enum only selects the protocol adapter inside the Custom boundary.
 */
public enum CustomApiFormat {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES;

    public static CustomApiFormat fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("api format is required");
        }
        return switch (code.trim().toUpperCase()) {
            case "CHAT_COMPLETIONS" -> CHAT_COMPLETIONS;
            case "RESPONSES" -> RESPONSES;
            case "ANTHROPIC_MESSAGES" -> ANTHROPIC_MESSAGES;
            default -> throw new IllegalArgumentException("Unknown api format: " + code);
        };
    }

    /** Canonical endpoint suffix appended to the normalized base URL. */
    public String endpointSuffix() {
        return switch (this) {
            case CHAT_COMPLETIONS -> "/chat/completions";
            case RESPONSES -> "/responses";
            case ANTHROPIC_MESSAGES -> "/messages";
        };
    }

    /** UI presentation label required by the frozen design. */
    public String presentationLabel() {
        return switch (this) {
            case ANTHROPIC_MESSAGES -> "Anthropic Messages (/v1/messages)";
            case CHAT_COMPLETIONS -> "Chat Completions (/chat/completions)";
            case RESPONSES -> "Responses (/responses)";
        };
    }
}
