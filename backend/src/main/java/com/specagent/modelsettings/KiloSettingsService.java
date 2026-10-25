package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.provider.KiloClientProperties;
import com.specagent.model.provider.ModelProviderException;
import com.sharedlibs.kilogateway.FreeModelPolicy;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 文件名:KiloSettingsService.java
 *
 * 用途:Kilo Gateway(第三个模型供应商,OpenAI 兼容)设置的应用服务:借助 Starter
 * 提供的 {@link KiloGatewayClient} 发现实时模型目录、探测候选密钥、保存/切换模型,
 * 并通过 {@link KiloRuntimeSettingsPort} 向推理侧提供运行时设置。
 *
 * <p>与 OpenCode Zen 相同的安全姿态:密钥只在内存中探测,对外只暴露脱敏后缀;
 * 免费模型匿名可用,付费模型必须显式提供密钥。绝不发送降级/备用模型请求
 * (Starter 的 fallback 仅在配置了备用模型时才会尝试,此处不配置)。
 */
@Service
public class KiloSettingsService implements KiloRuntimeSettingsPort {

    private static final String CONTEXT = "kilo";

    private final KiloSettingsRepository repository;
    private final KiloGatewayClient client;
    private final KiloGatewayProperties properties;
    private final ObjectMapper mapper;

    public KiloSettingsService(KiloSettingsRepository repository, KiloGatewayClient client,
                               KiloGatewayProperties properties, ObjectMapper mapper) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    public KiloSettingsStatus status() {
        return repository.find()
                .map(settings -> new KiloSettingsStatus(
                        true, mask(settings.maskedSuffix()), settings.selectedModel()))
                .orElseGet(KiloSettingsStatus::unconfigured);
    }

    /** 一次目录发现的结果:全部可用模型、免费子集,以及可达性探测应使用的模型。 */
    public record CandidateModels(List<String> allModels, List<String> freeModels,
                                  String recommendedProbeModel) {
    }

    /** 列出当前模型目录(公开接口,不需要密钥)。 */
    public CandidateModels listModels() {
        return discover();
    }

    /**
     * 只探测候选配置,绝不写仓储。提供了候选密钥时用它对上游发起一次最小
     * 可达性请求;免费模型无需密钥因此可跳过。
     */
    public CandidateModels probe(String apiKey) {
        CandidateModels models = discover();
        if (hasKey(apiKey)) {
            String probeModel = models.recommendedProbeModel();
            validateCredential(credentialForModel(probeModel, apiKey.trim()), probeModel);
        }
        return models;
    }

    /** 保存前对完整候选配置做全部校验,通过后才落一次 upsert。 */
    public KiloSettingsStatus save(String apiKeyInput, String selectedModel) {
        String model = requireModel(selectedModel);
        KiloSettings existing = repository.find().orElse(null);
        String key = hasKey(apiKeyInput)
                ? apiKeyInput.trim()
                : (existing != null && hasKey(existing.apiKey()) ? existing.apiKey() : null);
        CandidateModels models = discover();
        requireAvailable(models, model);
        validateCredential(credentialForModel(model, key), model);

        Instant now = Instant.now();
        repository.upsert(new KiloSettings(key, ApiKeyMasking.suffix(key), model,
                existing == null ? now : existing.createdAt(), now));
        return status();
    }

    /**
     * 只切换选中模型,复用已持久化的密钥。全部校验都在唯一一次 upsert 之前完成,
     * 因此切换失败时旧配置依然生效。
     */
    public KiloSettingsStatus changeModel(String selectedModel) {
        KiloSettings current = requireStored();
        String model = requireModel(selectedModel);
        CandidateModels models = discover();
        requireAvailable(models, model);
        validateCredential(credentialForModel(model, current.apiKey()), model);

        repository.upsert(new KiloSettings(current.apiKey(), current.maskedSuffix(), model,
                current.createdAt(), Instant.now()));
        return status();
    }

    /** 重新验证已持久化的配置,但不写任何东西。 */
    public KiloSettingsStatus validate() {
        KiloSettings current = requireStored();
        CandidateModels models = discover();
        String model = current.selectedModel();
        requireAvailable(models, model);
        validateCredential(credentialForModel(model, current.apiKey()), model);
        return status();
    }

    /**
     * 已存配置的只读投影,供 GA 目标解析读取模型与设置修订号(配置变更即漂移)。
     * 密钥只留在后端,绝不出服务层。
     */
    public java.util.Optional<KiloSettings> stored() {
        return repository.find();
    }

    /** 唯一会把完整密钥交给推理侧的服务方法。 */
    @Override
    public RuntimeKiloSettings requireRuntimeSettings() {
        KiloSettings settings = requireStored();
        if (settings.selectedModel() == null || settings.selectedModel().isBlank()) {
            throw ModelProviderException.notConfigured(CONTEXT, "Kilo Gateway settings are not configured");
        }
        return new RuntimeKiloSettings(
                credentialForModel(settings.selectedModel(), settings.apiKey()),
                settings.selectedModel(), "database:kilo_settings",
                settings.updatedAt() == null ? "unknown" : settings.updatedAt().toString(),
                properties.getBaseUrl());
    }

    /**
     * 本次调用实际应使用的凭据——Kilo 凭据边界的<b>唯一判定点</b>。
     *
     * <p>免费档(按既有可信规则 {@link FreeModelPolicy#isFreeModelId} 判定)恒为匿名:
     * 即便之前保存过付费密钥,免费模型的请求也绝不携带它。付费档必须显式提供密钥,
     * 否则失败关闭。Project Agent 推理与 GA 目标解析都只经由这一个方法取得凭据,
     * 因此不存在第二套模型分类或第二处凭据判定。
     */
    public String credentialForModel(String model, String apiKey) {
        if (FreeModelPolicy.isFreeModelId(model)) {
            return null;
        }
        if (!hasKey(apiKey)) {
            throw ModelProviderException.notConfigured(CONTEXT, "Paid Kilo models require an API key");
        }
        return apiKey;
    }

    /** 一次上游列举同时派生可执行目录与免费子集,不再重复拉取 /models。 */
    private CandidateModels discover() {
        JsonNode root = client.models();
        List<String> all = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    all.add(id);
                }
            }
        }
        List<String> models = all.stream().distinct().sorted().toList();
        if (models.isEmpty()) {
            throw ModelProviderException.invalidModel(CONTEXT,
                    "Kilo Gateway has no currently available models", null);
        }
        List<String> free = models.stream().filter(FreeModelPolicy::isFreeModelId).toList();
        String probeModel = models.stream().filter(model -> !free.contains(model))
                .findFirst().orElse(models.get(0));
        return new CandidateModels(models, free, probeModel);
    }

    /**
     * 用给定凭据对上游发起一次最小补全请求,证明该(密钥, 模型)对确实可达。
     * 免费模型匿名请求不带 Authorization;付费模型必须带密钥。
     */
    private void validateCredential(String apiKey, String model) {
        // 设置页允许选择付费模型,因此这里不使用仅免费的默认策略。
        KiloGatewayProperties probeProperties = KiloClientProperties.forCredential(
                properties, hasKey(apiKey) ? apiKey : "", false);
        probeProperties.setModel(model);
        KiloGatewayClient probeClient = probeClient(probeProperties);
        ObjectNode body = mapper.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", "ping");
        try {
            probeClient.chat(body);
        } catch (KiloGatewayException error) {
            throw translate(error);
        }
    }

    /**
     * 可达性探测使用的客户端。抽成可替换的受保护方法,使该探测能在单元测试里
     * 离线验证,而不必真的连到上游网关。
     */
    protected KiloGatewayClient probeClient(KiloGatewayProperties probeProperties) {
        return new KiloGatewayClient(probeProperties);
    }

    private static ModelProviderException translate(KiloGatewayException error) {
        int status = error.getStatusCode();
        if (status == 401 || status == 403) {
            return ModelProviderException.authentication(CONTEXT, error.getMessage(), status);
        }
        if (status == 429) {
            return ModelProviderException.rateLimited(CONTEXT, error.getMessage());
        }
        if (status >= 500) {
            return ModelProviderException.serverError(CONTEXT, error.getMessage(), status);
        }
        if (status < 0) {
            return ModelProviderException.connection(CONTEXT, error.getMessage(), error);
        }
        return ModelProviderException.providerRequestError(CONTEXT, error.getMessage(), status);
    }

    private static void requireAvailable(CandidateModels models, String model) {
        if (!models.allModels().contains(model)) {
            throw ModelProviderException.invalidModel(CONTEXT,
                    "Selected Kilo model is not currently available", null);
        }
    }

    private KiloSettings requireStored() {
        return repository.find().orElseThrow(() -> ModelProviderException.notConfigured(
                CONTEXT, "Kilo Gateway settings are not configured"));
    }

    private static String requireModel(String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A model must be selected");
        }
        return selectedModel.trim();
    }

    private static boolean hasKey(String apiKey) {
        return ApiKeyMasking.hasKey(apiKey);
    }

    private static String mask(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        return "••••" + suffix;
    }
}
