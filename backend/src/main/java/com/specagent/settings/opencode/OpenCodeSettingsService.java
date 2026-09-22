package com.specagent.settings.opencode;

import com.specagent.model.inference.OpenCodeRuntimeSettingsPort;
import com.specagent.model.inference.RuntimeOpenCodeSettings;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Coordinates probe/save without exposing the working key to the API layer. */
@Service
public class OpenCodeSettingsService implements OpenCodeRuntimeSettingsPort {

    static final String DATABASE_SOURCE = "database";
    static final String EXTERNAL_ENVIRONMENT_SOURCE = "external-environment";
    static final String EXTERNAL_CREDENTIAL_SOURCE = "external-environment:SPEC_AGENT_EVAL_OPENCODE_KEY";

    private final OpenCodeSettingsRepository repository;
    private final OpenCodeModelCatalog catalog;
    private final OpenCodeZenTransport transport;
    private final String runtimeSettingsSource;
    private final String externalApiKey;
    private final String externalSelectedModel;

    /** Default constructor retained for direct unit-test callers. */
    public OpenCodeSettingsService(OpenCodeSettingsRepository repository,
                                   OpenCodeModelCatalog catalog,
                                   OpenCodeZenTransport transport) {
        this(repository, catalog, transport, DATABASE_SOURCE, "", "");
    }

    @Autowired
    public OpenCodeSettingsService(OpenCodeSettingsRepository repository,
                                   OpenCodeModelCatalog catalog,
                                   OpenCodeZenTransport transport,
                                   @Value("${spec.agent.model.runtime-settings-source:database}")
                                   String runtimeSettingsSource,
                                   @Value("${spec.agent.model.external.api-key:}")
                                   String externalApiKey,
                                   @Value("${spec.agent.model.external.selected-model:}")
                                   String externalSelectedModel) {
        this.repository = repository;
        this.catalog = catalog;
        this.transport = transport;
        this.runtimeSettingsSource = runtimeSettingsSource;
        this.externalApiKey = externalApiKey;
        this.externalSelectedModel = externalSelectedModel;
    }

    public OpenCodeSettingsStatus status() {
        return repository.find()
                .map(settings -> new OpenCodeSettingsStatus(
                        true, OpenCodeSettingsStatusMask.mask(settings.maskedSuffix()), settings.selectedModel()))
                .orElseGet(OpenCodeSettingsStatus::unconfigured);
    }

    /** Probes a candidate in memory. This method never writes the repository. */
    public OpenCodeCandidateModels probe(String apiKey) {
        String candidate = requireKey(apiKey);
        OpenCodeCandidateModels models = currentCandidateModels(candidate);
        // A probe validates credential reachability using one currently
        // available model, but does not choose or persist a working model.
        transport.validateCredential(candidate, models.recommendedProbeModel());
        return models;
    }

    /**
     * Lists current models with the already persisted credential. The key is
     * resolved explicitly from storage; an empty request key is never
     * interpreted as "reuse the old key".
     */
    public OpenCodeCandidateModels listSavedKeyModels() {
        OpenCodeSettings settings = requireStoredSettings();
        return currentCandidateModels(settings.apiKey());
    }

    /** Revalidates the complete candidate configuration before one upsert. */
    public OpenCodeSettingsStatus save(String apiKey, String selectedModel) {
        String candidate = requireKey(apiKey);
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A model must be selected");
        }
        String model = selectedModel.trim();
        OpenCodeCandidateModels models = currentCandidateModels(candidate);
        if (!models.allModels().contains(model)) {
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
        OpenCodeCandidateModels models = currentCandidateModels(current.apiKey());
        if (!models.allModels().contains(model)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(current.apiKey(), model);

        repository.upsert(new OpenCodeSettings(
                current.apiKey(), current.maskedSuffix(), model,
                current.createdAt(), Instant.now()));
        return status();
    }

    /**
     * Revalidates the persisted configuration without writing anything.
     * Mirrors the OpenRouter contract: the reachability check runs against the
     * model that is actually stored, so a settings card can offer an explicit
     * "test again" action that never mutates the saved pair.
     */
    public OpenCodeSettingsStatus validate() {
        OpenCodeSettings current = requireStoredSettings();
        OpenCodeCandidateModels models = currentCandidateModels(current.apiKey());
        if (!models.allModels().contains(current.selectedModel())) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(current.apiKey(), current.selectedModel());
        return status();
    }

    /** The only normal service method that returns the full key to backend code. */
    @Override
    public RuntimeOpenCodeSettings requireRuntimeSettings() {
        if (EXTERNAL_ENVIRONMENT_SOURCE.equals(runtimeSettingsSource)) {
            return requireExternalRuntimeSettings();
        }
        if (!DATABASE_SOURCE.equals(runtimeSettingsSource)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "Unsupported OpenCode runtime settings source: " + runtimeSettingsSource);
        }
        OpenCodeSettings settings = requireStoredSettings();
        if (settings.apiKey() == null || settings.apiKey().isBlank()
                || settings.selectedModel() == null || settings.selectedModel().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "OpenCode settings are not configured");
        }
        // Model selection is gated at save/changeModel time against the live
        // provider list (free or paid); the runtime path itself stays
        // policy-free and never re-imposes a cost filter.
        return new RuntimeOpenCodeSettings(settings.apiKey(), settings.selectedModel(),
                "database:opencode_settings");
    }

    /**
     * Explicit live-evaluation source. Blank values are intentionally not
     * defaulted from the product database: evalLive must never inherit the
     * test database's provider row or a product-local model selection.
     */
    private RuntimeOpenCodeSettings requireExternalRuntimeSettings() {
        if (externalApiKey == null || externalApiKey.isBlank()
                || externalSelectedModel == null || externalSelectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "Live OpenCode provider configuration is missing: set "
                            + "SPEC_AGENT_EVAL_OPENCODE_KEY and "
                            + "SPEC_AGENT_EVAL_OPENCODE_MODEL; test database settings are not used");
        }
        // Product settings follow the same live-list policy as any other
        // provider: the explicitly isolated live evaluation source may select
        // any exact model exposed by the provider. Qualification validates
        // reachability and schema compliance before a model is used as a
        // reference; it must not be constrained by product cost policy.
        return new RuntimeOpenCodeSettings(externalApiKey.trim(), externalSelectedModel.trim(),
                EXTERNAL_CREDENTIAL_SOURCE);
    }

    /** Full provider catalog plus the free subset, from one live call. */
    private OpenCodeCandidateModels currentCandidateModels(String apiKey) {
        List<String> allModels = catalog.listAllModels(apiKey);
        if (allModels.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode has no currently available models");
        }
        List<String> freeModels = allModels.stream()
                .filter(OpenCodeModelCatalog::isFreeModel)
                .toList();
        // Reachability probe prefers a free model so the credential check
        // never spends credit; any exposed model is an acceptable fallback.
        String probeModel = !freeModels.isEmpty() ? freeModels.get(0) : allModels.get(0);
        return new OpenCodeCandidateModels(allModels, freeModels, probeModel);
    }

    /** One probe/list result: everything exposed, the free subset, and the
     * model a reachability check should run against. */
    public record OpenCodeCandidateModels(List<String> allModels, List<String> freeModels,
                                          String recommendedProbeModel) {
    }

    private OpenCodeSettings requireStoredSettings() {
        return repository.find().orElseThrow(
                () -> new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                        "OpenCode settings are not configured"));
    }

    private static String requireModel(String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A model must be selected");
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
