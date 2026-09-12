package com.specagent.settings.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class OpenRouterSettingsService {

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

    /** Candidate probe: validates key reachability + free list without saving. */
    public List<String> probeCandidate(String apiKey) {
        String key = requireKey(apiKey);
        List<String> all = fetchModelIds(key, "openrouter");
        List<String> free = all.stream().filter(OpenRouterGatewaySupport::isFreeModelId).sorted().toList();
        if (free.isEmpty()) {
            throw ModelProviderException.invalidModel("openrouter", "No free models available", null);
        }
        // Credential reachability is proven by a successful model list; the
        // compatibility probe runs per selected model at validate time.
        return free;
    }

    public List<String> listSavedKeyModels() {
        OpenRouterSettings s = requireStored();
        List<String> all = fetchModelIds(s.apiKey(), "openrouter");
        return all.stream().filter(OpenRouterGatewaySupport::isFreeModelId).sorted().toList();
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
        if (!OpenRouterGatewaySupport.isFreeModelId(model)) {
            // Still verify against live list so paid ids fail with a clear error.
            List<String> free = probeCandidate(resolvedKey);
            if (!free.contains(model)) {
                throw ModelProviderException.invalidModel("openrouter",
                        "Selected OpenRouter model is not an available free model", null);
            }
        } else {
            List<String> free = probeCandidate(resolvedKey);
            if (!free.contains(model)) {
                throw ModelProviderException.invalidModel("openrouter",
                        "Selected OpenRouter model is not currently available", null);
            }
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
        List<String> free = probeCandidate(s.apiKey());
        if (!free.contains(s.selectedModel())) {
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

    private List<String> fetchModelIds(String apiKey, String context) {
        var adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        String url = OpenRouterGatewaySupport.BASE_URL + "/models";
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, url, adapter.authHeaders(apiKey),
                ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        JsonNode root = result.json();
        if (root == null) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list unsupported");
        }
        return OpenRouterModelQualification.qualifiedIds(root, context);
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
