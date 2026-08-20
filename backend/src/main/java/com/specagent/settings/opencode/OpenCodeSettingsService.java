package com.specagent.settings.opencode;

import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Coordinates probe/save without exposing the working key to the API layer. */
@Service
public class OpenCodeSettingsService {

    private final OpenCodeSettingsRepository repository;
    private final OpenCodeModelCatalog catalog;
    private final OpenCodeZenTransport transport;

    public OpenCodeSettingsService(OpenCodeSettingsRepository repository,
                                   OpenCodeModelCatalog catalog,
                                   OpenCodeZenTransport transport) {
        this.repository = repository;
        this.catalog = catalog;
        this.transport = transport;
    }

    public OpenCodeSettingsStatus status() {
        return repository.find()
                .map(settings -> new OpenCodeSettingsStatus(
                        true, OpenCodeSettingsStatusMask.mask(settings.maskedSuffix()), settings.selectedModel()))
                .orElseGet(OpenCodeSettingsStatus::unconfigured);
    }

    /** Probes a candidate in memory. This method never writes the repository. */
    public List<String> probe(String apiKey) {
        String candidate = requireKey(apiKey);
        List<String> freeModels = currentFreeModels(candidate);
        // A probe validates credential reachability using one currently allowed
        // free model, but does not choose or persist a working model.
        transport.validateCredential(candidate, freeModels.get(0));
        return List.copyOf(freeModels);
    }

    /**
     * Lists current free models with the already persisted credential. The
     * key is resolved explicitly from storage; an empty request key is never
     * interpreted as "reuse the old key".
     */
    public List<String> listSavedKeyModels() {
        OpenCodeSettings settings = requireStoredSettings();
        return List.copyOf(currentFreeModels(settings.apiKey()));
    }

    /** Revalidates the complete candidate configuration before one upsert. */
    public OpenCodeSettingsStatus save(String apiKey, String selectedModel) {
        String candidate = requireKey(apiKey);
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A free model must be selected");
        }
        String model = selectedModel.trim();
        List<String> freeModels = currentFreeModels(candidate);
        if (!model.endsWith("-free") || !freeModels.contains(model)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(candidate, model);

        Instant now = Instant.now();
        repository.upsert(new OpenCodeSettings(candidate, suffix(candidate), model, now, now));
        return status();
    }

    /**
     * Changes only the selected model using the persisted key. All provider
     * validation happens before the single upsert, so a failed switch leaves
     * the previous working settings active and preserves credential metadata.
     */
    public OpenCodeSettingsStatus changeModel(String selectedModel) {
        OpenCodeSettings current = requireStoredSettings();
        String model = requireModel(selectedModel);
        List<String> freeModels = currentFreeModels(current.apiKey());
        if (!model.endsWith("-free") || !freeModels.contains(model)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(current.apiKey(), model);

        repository.upsert(new OpenCodeSettings(
                current.apiKey(), current.maskedSuffix(), model,
                current.createdAt(), Instant.now()));
        return status();
    }

    /** The only normal service method that returns the full key to backend code. */
    public RuntimeOpenCodeSettings requireRuntimeSettings() {
        OpenCodeSettings settings = requireStoredSettings();
        if (settings.apiKey() == null || settings.apiKey().isBlank()
                || settings.selectedModel() == null || settings.selectedModel().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "OpenCode settings are not configured");
        }
        if (!settings.selectedModel().endsWith("-free")) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Configured OpenCode model is not currently allowed");
        }
        return new RuntimeOpenCodeSettings(settings.apiKey(), settings.selectedModel());
    }

    private List<String> currentFreeModels(String apiKey) {
        List<String> models = catalog.listFreeModels(apiKey);
        if (models.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode has no currently available free models");
        }
        return models;
    }

    private OpenCodeSettings requireStoredSettings() {
        return repository.find().orElseThrow(
                () -> new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                        "OpenCode settings are not configured"));
    }

    private static String requireModel(String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A free model must be selected");
        }
        return selectedModel.trim();
    }

    private static String requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenCode API key must not be blank");
        }
        return apiKey.trim();
    }

    private static String suffix(String apiKey) {
        // A very short candidate must never be returned in full as its own
        // masked suffix. The normal OpenCode key is longer, but fail closed.
        return apiKey.length() <= 4 ? "" : apiKey.substring(apiKey.length() - 4);
    }

    private static final class OpenCodeSettingsStatusMask {
        private static String mask(String suffix) {
            return suffix == null || suffix.isBlank() ? "••••" : "••••" + suffix;
        }
    }
}
