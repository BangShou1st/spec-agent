package com.specagent.settings.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CustomProviderSettingsService {

    private final CustomProviderSettingsRepository repository;
    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final CompatibilityProbeService probe;
    private final HttpClient client;

    public CustomProviderSettingsService(CustomProviderSettingsRepository repository, ObjectMapper mapper,
                                         ProtocolAdapterRegistry registry, CompatibilityProbeService probe) {
        this.repository = repository;
        this.mapper = mapper;
        this.registry = registry;
        this.probe = probe;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    public record Status(boolean configured, String apiFormat, String baseUrl, String endpointPreview,
                         boolean hasKey, String maskedKey, String selectedModel, boolean manualModel,
                         long configRevision, boolean validated) {
    }

    public record Discovery(List<String> models, boolean manualModel) {
    }

    public Status status() {
        return repository.find()
                .map(s -> {
                    String preview;
                    try {
                        preview = ProviderUrlSecurity.canonicalEndpoint(s.baseUrl(),
                                CustomApiFormat.fromCode(s.apiFormat()));
                    } catch (Exception ex) {
                        preview = null;
                    }
                    boolean validated = s.validatedRevision() != null
                            && s.validatedRevision() == s.configRevision();
                    return new Status(true, s.apiFormat(), s.baseUrl(), preview,
                            s.apiKey() != null && !s.apiKey().isBlank(),
                            mask(s.maskedSuffix()), s.selectedModel(), "MANUAL".equals(s.modelSource()),
                            s.configRevision(), validated);
                })
                .orElseGet(() -> new Status(false, CustomApiFormat.CHAT_COMPLETIONS.name(),
                        "", null, false, null, "", false, 0, false));
    }

    /**
     * Model discovery for a draft (unsaved) config. 404/405/501 -> manual.
     * Key semantics mirror save: a non-empty provided key wins; null reuses
     * the stored key when one exists; explicit empty means unauthenticated.
     * The stored key is never returned.
     */
    public Discovery discover(String formatCode, String baseUrlInput, String apiKeyInput) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        String key;
        if (apiKeyInput != null && !apiKeyInput.isBlank()) {
            key = apiKeyInput.trim();
        } else if (apiKeyInput == null) {
            key = repository.find().map(CustomProviderSettings::apiKey).orElse(null);
            if (key != null && key.isBlank()) {
                key = null;
            }
        } else {
            key = null;
        }
        ProtocolAdapter adapter = registry.require(format);
        String url = normalized + "/models";
        String context = "custom-" + format.name().toLowerCase();
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, url, adapter.authHeaders(key),
                ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        if (result.status() == 404 || result.status() == 405 || result.status() == 501) {
            return new Discovery(List.of(), true);
        }
        if (result.json() == null) {
            return new Discovery(List.of(), true);
        }
        List<String> ids = adapter.parseModelList(result.json(), context);
        return new Discovery(ids, false);
    }

    /**
     * Save with revision invalidation. {@code apiKeyInput}: null retains the
     * stored key, empty clears to no-key (local services), non-empty sets new.
     */
    public Status save(String formatCode, String baseUrlInput, String apiKeyInput, String modelInput,
                       String modelSourceInput) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        String model = ProviderUrlSecurity.normalizeModelId(modelInput);
        String modelSource = "MANUAL".equals(modelSourceInput) ? "MANUAL" : "DISCOVERED";
        CustomProviderSettings existing = repository.find().orElse(null);
        String resolvedKey;
        String masked;
        if (apiKeyInput == null) {
            resolvedKey = existing == null ? null : existing.apiKey();
            masked = existing == null ? null : existing.maskedSuffix();
        } else if (apiKeyInput.isBlank()) {
            resolvedKey = null;
            masked = null;
        } else {
            resolvedKey = apiKeyInput.trim();
            masked = suffix(resolvedKey);
        }
        Instant now = Instant.now();
        if (existing != null && existing.apiFormat().equals(format.name())
                && existing.baseUrl().equals(normalized)
                && equalsNullable(existing.apiKey(), resolvedKey)
                && existing.selectedModel().equals(model)
                && modelSource.equals(existing.modelSource() == null ? "DISCOVERED" : existing.modelSource())) {
            return status();
        }
        long nextRevision = existing == null ? 1 : existing.configRevision() + 1;
        repository.upsert(new CustomProviderSettings(format.name(), normalized, resolvedKey, masked, model, modelSource,
                nextRevision, null, existing == null ? now : existing.createdAt(), now, null));
        return status();
    }

    /** Compatibility test on the saved configuration. */
    public Status validate() {
        CustomProviderSettings s = requireStored();
        CustomApiFormat format = CustomApiFormat.fromCode(s.apiFormat());
        if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
            throw ModelProviderException.providerRequestError("custom",
                    "Anthropic Messages cannot serve the required JSON_OBJECT contract in V1", null);
        }
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(s.baseUrl());
        String model = ProviderUrlSecurity.normalizeModelId(s.selectedModel());
        probe.probeCustom(format, normalized, s.apiKey(), model);
        repository.markValidated(s.configRevision());
        return status();
    }

    public void requireActivatable() {
        CustomProviderSettings s = requireStored();
        if (s.baseUrl() == null || s.baseUrl().isBlank()
                || s.selectedModel() == null || s.selectedModel().isBlank()) {
            throw ModelProviderException.notConfigured("custom", "Custom provider is not configured");
        }
        ProviderUrlSecurity.validateAndNormalizeBaseUrl(s.baseUrl());
        CustomApiFormat format = CustomApiFormat.fromCode(s.apiFormat());
        if (format == CustomApiFormat.ANTHROPIC_MESSAGES) {
            throw ModelProviderException.notConfigured("custom",
                    "Anthropic Messages is not activatable in V1");
        }
        ProviderUrlSecurity.normalizeModelId(s.selectedModel());
        if (s.validatedRevision() == null || s.validatedRevision() != s.configRevision()) {
            throw ModelProviderException.notConfigured("custom",
                    "Custom configuration is not validated for the current revision");
        }
    }

    public CustomProviderSettings requireStored() {
        return repository.find()
                .orElseThrow(() -> ModelProviderException.notConfigured("custom", "Custom provider is not configured"));
    }

    /** API-boundary preview so controllers never depend on model packages. */
    public String previewEndpoint(String formatCode, String baseUrlInput) {
        try {
            CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
            String normalized = ProviderUrlSecurity.normalizeBaseUrl(baseUrlInput);
            return ProviderUrlSecurity.canonicalEndpoint(normalized, format);
        } catch (Exception ex) {
            return null;
        }
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

    static String mask(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        return "••••" + suffix;
    }
}
