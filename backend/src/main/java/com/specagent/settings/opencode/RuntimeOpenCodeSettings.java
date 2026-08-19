package com.specagent.settings.opencode;

/** Backend-only projection consumed by the production model gateway. */
public record RuntimeOpenCodeSettings(String apiKey, String selectedModel) {
}
