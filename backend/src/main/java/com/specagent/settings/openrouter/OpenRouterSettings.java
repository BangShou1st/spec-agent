package com.specagent.settings.openrouter;

import java.time.Instant;

public record OpenRouterSettings(
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        long configRevision,
        Long validatedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {
}
