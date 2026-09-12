package com.specagent.settings.custom;

import java.time.Instant;

public record CustomProviderSettings(
        String apiFormat,
        String baseUrl,
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        String modelSource,
        long configRevision,
        Long validatedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {
}
