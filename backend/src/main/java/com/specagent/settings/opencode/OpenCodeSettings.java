package com.specagent.settings.opencode;

import java.time.Instant;

/** The persisted global OpenCode working configuration. */
public record OpenCodeSettings(
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        Instant createdAt,
        Instant updatedAt) {
}
