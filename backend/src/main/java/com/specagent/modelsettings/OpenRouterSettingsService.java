package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.OpenRouterRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeOpenRouterSettings;
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

/**
 * 文件名:OpenRouterSettingsService.java
 *
 * 用途:OpenRouter 提供商设置的应用服务:密钥探测、模型发现(带资格过滤)、
 * 保存(变更即失效验证)、兼容性验证,并通过 OpenRouterRuntimeSettingsPort
 * 向推理侧提供经过激活门禁的运行时设置。
 */
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
     * 一次模型发现的结果:全部可展示的提供商模型,加上用于可达性探测的
     * 免费且通过资格筛选的子集。
     */
    public record CandidateModels(List<String> allModels, List<String> freeModels) {
    }

    /** 候选密钥探测:验证密钥可达性与模型列表,但不保存任何东西。 */
    public CandidateModels probeCandidate(String apiKey) {
        String key = requireKey(apiKey);
        CandidateModels models = discover(key);
        if (models.freeModels().isEmpty()) {
            throw ModelProviderException.invalidModel("openrouter", "No free models available", null);
        }
        // 密钥可达性由"成功拉到模型列表"来证明;兼容性探测在 validate 时对所选模型执行
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

    /** 保存:密钥或模型发生变化时,先前的验证结果即被作废。 */
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
        // 模型选择遵循提供商实时列表:免费与付费 id 都可保存,但该 id 必须
        // 当前真实存在,且保存的配置对必须在激活前通过真正的兼容性探测
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

    /** 对已保存的配置做兼容性测试,通过后写入验证修订号。 */
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
     * 推理端口投影:先取已存配置,再走激活资格检查,失败即拒绝(fail closed)——
     * 等价于网关历史上每次请求执行的 requireStored + requireActivatable 序列。
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
