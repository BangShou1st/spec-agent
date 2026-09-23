package com.specagent.modelsettings;

import java.time.Instant;

public record CustomProviderSettings(
        String apiFormat,
        String baseUrl,
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        String modelSource,
        String displayName,
        long configRevision,
        Long validatedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {
}
