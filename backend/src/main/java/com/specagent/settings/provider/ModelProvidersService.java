package com.specagent.settings.provider;

import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProvider;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Row-per-provider management.
 *
 * <p>Every provider the user can create is a row: adding one is an insert, so
 * the settings page never needs a code change to support "one more gateway".
 * Preset rows are seeded by migration and stay distinguishable through
 * {@link ModelProvider}, which is what keeps OpenCode Zen's direct-transport
 * request shape and OpenRouter's qualification pass special-cased.
 */
@Service
public class ModelProvidersService {

    private static final Set<String> FORMATS = Set.of(
            "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES");

    private final ModelProviderRepository repository;
    private final ProviderModelCatalogService catalog;
    private final CompatibilityProbeService probe;

    public ModelProvidersService(ModelProviderRepository repository,
                                 ProviderModelCatalogService catalog,
                                 CompatibilityProbeService probe) {
        this.repository = repository;
        this.catalog = catalog;
        this.probe = probe;
    }

    /**
     * A partial update. {@code null} means "keep the stored value", which is
     * what lets the settings dialog submit only the fields the user touched;
     * an empty {@code apiKey} explicitly clears the credential.
     */
    public record Patch(String preset, String displayName, String apiFormat, String baseUrl,
                        String apiKey, String selectedModel, String modelSource) {
    }

    public List<ModelProviderRecord> list() {
        return repository.findAll();
    }

    public ModelProviderRecord require(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Provider id is required");
        }
        return repository.findById(id).orElseThrow(() -> ModelProviderException.notConfigured(
                "providers", "Unknown model provider: " + id));
    }

    public ModelProviderRecord findFirstByPreset(ModelProvider preset) {
        return repository.findFirstByPreset(preset.name()).orElseThrow(
                () -> ModelProviderException.notConfigured(preset.name().toLowerCase(),
                        preset.defaultDisplayName() + " is not configured"));
    }

    /**
     * Quick add. Only user-defined providers can be created: presets are
     * seeded rows whose base URL and request shape are fixed by the backend.
     */
    public ModelProviderRecord create(Patch patch) {
        String displayName = requireDisplayName(patch.displayName());
        String apiFormat = requireFormat(patch.apiFormat());
        String baseUrl = ProviderUrlSecurity.validateAndNormalizeBaseUrl(patch.baseUrl());
        String model = ProviderUrlSecurity.normalizeModelId(patch.selectedModel());
        String apiKey = blankToNull(patch.apiKey());
        Instant now = Instant.now();
        ModelProviderRecord record = new ModelProviderRecord(
                UUID.randomUUID(),
                // Preset rows are seeded, never created through the API.
                ModelProvider.CUSTOM,
                displayName,
                apiFormat,
                baseUrl,
                apiKey,
                suffix(apiKey),
                model,
                "MANUAL".equals(patch.modelSource()) ? "MANUAL" : "DISCOVERED",
                1L,
                null,
                nextPosition(),
                now,
                now,
                null);
        repository.insert(record);
        return record;
    }

    /**
     * Applies a patch. Any change to the credential, protocol or base URL
     * bumps the revision and drops the previous validation, so activation can
     * never ride on a test that no longer describes the stored provider.
     */
    public ModelProviderRecord update(UUID id, Patch patch) {
        ModelProviderRecord current = require(id);
        String displayName = patch.displayName() == null
                ? current.displayName() : requireDisplayName(patch.displayName());
        String apiFormat = patch.apiFormat() == null
                ? current.apiFormat() : requireFormat(patch.apiFormat());
        String baseUrl = patch.baseUrl() == null
                ? current.baseUrl() : ProviderUrlSecurity.validateAndNormalizeBaseUrl(patch.baseUrl());
        String model = patch.selectedModel() == null
                ? current.selectedModel() : ProviderUrlSecurity.normalizeModelId(patch.selectedModel());
        String apiKey;
        String maskedSuffix;
        if (patch.apiKey() == null) {
            apiKey = current.apiKey();
            maskedSuffix = current.maskedSuffix();
        } else {
            apiKey = blankToNull(patch.apiKey());
            maskedSuffix = suffix(apiKey);
        }
        String modelSource = patch.modelSource() == null
                ? current.modelSource()
                : ("MANUAL".equals(patch.modelSource()) ? "MANUAL" : "DISCOVERED");

        boolean identityChanged = !equalsNullable(current.apiFormat(), apiFormat)
                || !equalsNullable(current.baseUrl(), baseUrl)
                || !equalsNullable(current.apiKey(), apiKey);
        boolean changed = identityChanged
                || !equalsNullable(current.selectedModel(), model)
                || !equalsNullable(current.displayName(), displayName)
                || !equalsNullable(current.modelSource(), modelSource);
        if (!changed) {
            return current;
        }
        long revision = identityChanged ? current.configRevision() + 1 : current.configRevision();
        ModelProviderRecord updated = new ModelProviderRecord(
                current.id(), current.preset(), displayName, apiFormat, baseUrl, apiKey, maskedSuffix,
                model, modelSource, revision,
                identityChanged ? null : current.validatedRevision(),
                current.position(), current.createdAt(), Instant.now(),
                identityChanged ? null : current.validatedAt());
        repository.update(updated);
        return updated;
    }

    public void delete(UUID id) {
        require(id);
        repository.delete(id);
    }

    /** Draft discovery for an unsaved edit; the stored row supplies a fallback key. */
    public ProviderModelCatalogService.Discovery discover(UUID id, String apiFormat, String baseUrl,
                                                          String apiKey) {
        ModelProviderRecord stored = id == null ? null : require(id);
        String format = apiFormat != null && !apiFormat.isBlank()
                ? apiFormat : (stored == null ? CustomApiFormat.CHAT_COMPLETIONS.name() : stored.apiFormat());
        String target = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl : (stored == null ? "" : stored.baseUrl());
        if (target.isBlank()) {
            throw new IllegalArgumentException("API Base URL is required");
        }
        return catalog.probe(ModelProvider.CUSTOM.name(), format, target, apiKey, stored);
    }

    /** Catalog using the already stored credential; never asks for the key again. */
    public ProviderModelCatalogService.Discovery listModels(UUID id) {
        return catalog.list(require(id));
    }

    /**
     * Reachability test on the stored pair. A failure is reported but never
     * mutates the saved configuration, so a working provider keeps working.
     */
    public ModelProviderRecord validate(UUID id) {
        ModelProviderRecord record = require(id);
        CustomApiFormat format = CustomApiFormat.fromCode(record.apiFormat());
        if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
            throw ModelProviderException.providerRequestError("custom",
                    "Anthropic Messages cannot serve the required JSON_OBJECT contract in V1", null);
        }
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(record.baseUrl());
        String model = ProviderUrlSecurity.normalizeModelId(record.selectedModel());
        var discovery = catalog.list(record);
        if (!discovery.manualModel() && !discovery.allModels().contains(model)) {
            throw ModelProviderException.invalidModel("custom",
                    "Selected model is not currently available", null);
        }
        probe.probeCustom(format, normalized, record.apiKey(), model);
        repository.markValidated(record.id(), record.configRevision());
        return require(id);
    }

    /** Activation gate: a row must be configured and tested at its current revision. */
    public void requireActivatable(ModelProviderRecord record) {
        if (!record.configured()) {
            throw ModelProviderException.notConfigured("custom",
                    record.effectiveDisplayName() + " is not configured");
        }
        if (record.preset() == ModelProvider.CUSTOM) {
            ProviderUrlSecurity.validateAndNormalizeBaseUrl(record.baseUrl());
            CustomApiFormat format = CustomApiFormat.fromCode(record.apiFormat());
            if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
                throw ModelProviderException.notConfigured("custom",
                        "Anthropic Messages is not activatable in V1");
            }
            ProviderUrlSecurity.normalizeModelId(record.selectedModel());
        }
        if (!record.validated()) {
            throw ModelProviderException.notConfigured("custom",
                    record.effectiveDisplayName() + " is not validated for the current revision");
        }
    }

    private int nextPosition() {
        return repository.findAll().stream().mapToInt(ModelProviderRecord::position).max().orElse(-1) + 1;
    }

    private static String requireDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Display name is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Display name must be at most 64 characters");
        }
        return trimmed;
    }

    private static String requireFormat(String raw) {
        String format = raw == null ? "" : raw.trim().toUpperCase();
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Unknown api format");
        }
        return format;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    static String suffix(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }
}
