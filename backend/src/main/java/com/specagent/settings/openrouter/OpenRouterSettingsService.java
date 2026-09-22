package com.specagent.settings.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.inference.OpenRouterRuntimeSettingsPort;
import com.specagent.model.inference.RuntimeOpenRouterSettings;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenRouterGatewaySupport;
import com.specagent.model.provider.OpenRouterModelQualification;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenRouterSettingsService implements OpenRouterRuntimeSettingsPort {

    private final OpenRouterSettingsRepository repository;
    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final CompatibilityProbeService probe;
    private final HttpClient client;

    public OpenRouterSettingsService(OpenRouterSettingsRepository repository, ObjectMapper mapper,
                                     ProtocolAdapterRegistry registry, CompatibilityProbeService probe) {
        this.repository = repository;
        this.mapper = mapper;
        this.registry = registry;
        this.probe = probe;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    public record Status(boolean configured, String maskedKey, String selectedModel,
                         long configRevision, boolean validated, boolean activeHint) {
    }

    public Status status(boolean isActive) {
        return repository.find()
                .map(s -> new Status(true, mask(s.maskedSuffix()), s.selectedModel(),
                        s.configRevision(), s.validatedRevision() != null
                                && s.validatedRevision() == s.configRevision(),
                        isActive))
                .orElseGet(() -> new Status(false, null, null, 0, false, isActive));
    }

    /**
     * One model-discovery result: every displayable provider model plus the
     * free qualified subset used for the reachability probe.
     */
    public record CandidateModels(List<String> allModels, List<String> freeModels) {
    }

    /** Candidate probe: validates key reachability + model list without saving. */
    public CandidateModels probeCandidate(String apiKey) {
        String key = requireKey(apiKey);
        CandidateModels models = discover(key);
        if (models.freeModels().isEmpty()) {
            throw ModelProviderException.invalidModel("openrouter", "No free models available", null);
        }
        // Credential reachability is proven by a successful model list; the
        // compatibility probe runs per selected model at validate time.
        return models;
    }

    public CandidateModels listSavedKeyModels() {
        OpenRouterSettings s = requireStored();
        return discover(s.apiKey());
    }

    private CandidateModels discover(String apiKey) {
        var ids = OpenRouterModelQualification.qualifiedModelIds(
                fetchModelRoot(apiKey, "openrouter"), "openrouter");
        List<String> free = ids.all().stream()
                .filter(OpenRouterGatewaySupport::isFreeModelId)
                .toList();
        return new CandidateModels(ids.all(), free);
    }

    /** Save invalidates prior validation when key or model changes. */
    public Status save(String apiKeyInput, String modelInput) {
        String model = ProviderUrlSecurity.normalizeModelId(modelInput);
        OpenRouterSettings existing = repository.find().orElse(null);
        String resolvedKey;
        if (apiKeyInput == null || apiKeyInput.isBlank()) {
            if (existing == null) {
                throw new IllegalArgumentException("OpenRouter API key is required");
            }
            resolvedKey = existing.apiKey();
        } else {
            resolvedKey = apiKeyInput.trim();
        }
        // Selection follows the live provider list: free and paid ids are
        // both saveable, but the id must exist right now and the saved pair
        // must pass the real compatibility probe before activation.
        CandidateModels models = probeCandidate(resolvedKey);
        if (!models.allModels().contains(model)) {
            throw ModelProviderException.invalidModel("openrouter",
                    "Selected OpenRouter model is not currently available", null);
        }
        Instant now = Instant.now();
        if (existing != null && existing.apiKey().equals(resolvedKey)
                && existing.selectedModel().equals(model)) {
            return status(false);
        }
        long nextRevision = existing == null ? 1 : existing.configRevision() + 1;
        repository.upsert(new OpenRouterSettings(resolvedKey, suffix(resolvedKey), model,
                nextRevision, null, existing == null ? now : existing.createdAt(), now, null));
        return status(false);
    }

/** Compatibility test on the saved configuration. Sets validated revision. */
    public Status validate() {
        OpenRouterSettings s = requireStored();
        CandidateModels models = probeCandidate(s.apiKey());
        if (!models.allModels().contains(s.selectedModel())) {
            throw ModelProviderException.invalidModel("openrouter",
                    "Selected OpenRouter model is not currently available", null);
        }
        probe.probeOpenRouter(s.apiKey(), s.selectedModel());
        repository.markValidated(s.configRevision());
        return status(false);
    }

    public void requireActivatable() {
        OpenRouterSettings s = requireStored();
        if (s.selectedModel() == null || s.selectedModel().isBlank()
                || s.apiKey() == null || s.apiKey().isBlank()) {
            throw ModelProviderException.notConfigured("openrouter", "OpenRouter is not configured");
        }
        if (s.validatedRevision() == null || s.validatedRevision() != s.configRevision()) {
            throw ModelProviderException.notConfigured("openrouter",
                    "OpenRouter configuration is not validated for the current revision");
        }
    }

    public OpenRouterSettings requireStored() {
        return repository.find()
                .orElseThrow(() -> ModelProviderException.notConfigured("openrouter", "OpenRouter is not configured"));
    }

    /**
     * Inference-port projection: stored settings gated by the activatable
     * check, fail closed — the historical requireStored + requireActivatable
     * sequence the gateway ran per request.
     */
    @Override
    public RuntimeOpenRouterSettings requireRuntimeSettings() {
        OpenRouterSettings s = requireStored();
        requireActivatable();
        return new RuntimeOpenRouterSettings(s.apiKey(), s.selectedModel());
    }

    private JsonNode fetchModelRoot(String apiKey, String context) {
        var adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String url = OpenRouterGatewaySupport.BASE_URL + "/models";
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, url, adapter.authHeaders(apiKey),
                ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        JsonNode root = result.json();
        if (root == null) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list unsupported");
        }
        return root;
    }

    private static String requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenRouter API key is required");
        }
        return apiKey.trim();
    }

    static String suffix(String key) {
        if (key == null || key.length() < 4) {
            return key == null ? "" : key;
        }
        return key.substring(key.length() - 4);
    }

    static String mask(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        return "••••" + suffix;
    }
}
