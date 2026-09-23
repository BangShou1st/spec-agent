package com.specagent.modelsettings;

import com.specagent.model.contract.ModelProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored provider: a preset kind plus this row's own credential, protocol
 * and model selection.
 *
 * <p>This is the single shape the settings page, the activation gate and the
 * runtime dispatch all read. {@code apiKey} is never returned to the browser —
 * API projections expose {@code maskedSuffix} instead.
 */
public record ModelProviderRecord(
        UUID id,
        ModelProvider preset,
        String displayName,
        String apiFormat,
        String baseUrl,
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        String modelSource,
        long configRevision,
        Long validatedRevision,
        int position,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {

    /** True when a stored key exists, without exposing it. */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** A row is "validated" only for the revision that was actually tested. */
    public boolean validated() {
        return validatedRevision != null && validatedRevision == configRevision;
    }

    /** Saved model came from manual entry rather than the provider catalog. */
    public boolean manualModel() {
        return "MANUAL".equals(modelSource);
    }

    /** Configured means the row can actually serve inference right now. */
    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && selectedModel != null && !selectedModel.isBlank()
                && (!preset.requiresApiKey() || hasKey());
    }

    /** The label shown on the card header and the provider pill. */
    public String effectiveDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return preset.defaultDisplayName();
    }
}
