package com.specagent.model.contract;

/**
 * Versioned prompt handed to a model gateway for one agent reasoning step.
 *
 * <p>A prompt is composed of a fixed system prompt (system policy plus task
 * instruction) and a user prompt carrying the task code and the runtime context
 * JSON. The version identifies which production prompt contract was used so
 * trace entries can attribute model output to a prompt version.
 */
public record ModelPrompt(
        String version,
        String systemPrompt,
        String userPrompt
) {
    public ModelPrompt {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Prompt version is required");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt is required");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt is required");
        }
    }
}
