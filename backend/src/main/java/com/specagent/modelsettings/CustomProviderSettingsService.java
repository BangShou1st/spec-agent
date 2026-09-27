package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
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

/**
 * 文件名:CustomProviderSettingsService.java
 *
 * 用途:自定义模型提供商(兼容 OpenAI/Anthropic 协议的第三方服务)设置的应用服务,
 * 负责配置的读取、保存(带配置版本号失效)、模型发现、兼容性验证,并通过
 * CustomRuntimeSettingsPort 向推理侧提供激活前的校验门禁(未验证的配置不可用)。
 */
@Service
public class CustomProviderSettingsService implements CustomRuntimeSettingsPort {

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
                         String displayName, long configRevision, boolean validated) {
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
                            normalizeDisplayName(s.displayName()), s.configRevision(), validated);
                })
                .orElseGet(() -> new Status(false, CustomApiFormat.CHAT_COMPLETIONS.name(),
                        "", null, false, null, "", false, null, 0, false));
    }

    /**
     * 针对未保存草稿配置的模型发现。404/405/501 一律退回手动填模型模式。
     * 密钥语义与保存一致:传入非空密钥则优先使用;传 null 时复用已存密钥(若有);
     * 显式传空串表示无鉴权(本地服务)。已存密钥本身不会返回给前端。
     *
     * 来源边界:已存密钥只在同一来源(scheme/host/有效端口)上复用;草稿
     * URL 指向不同来源时必须显式输入新凭据,旧密钥绝不自动携带到新目标。
     */
    public Discovery discover(String formatCode, String baseUrlInput, String apiKeyInput) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        CustomProviderSettings stored = repository.find().orElse(null);
        String key;
        if (apiKeyInput != null && !apiKeyInput.isBlank()) {
            key = apiKeyInput.trim();
        } else if (apiKeyInput == null && stored != null && stored.apiKey() != null
                && !stored.apiKey().isBlank()) {
            if (stored.baseUrl() == null
                    || !ProviderUrlSecurity.sameOrigin(normalized, stored.baseUrl())) {
                throw ModelProviderException.notConfigured("custom",
                        "Base URL points to a different origin; enter the API key for it explicitly");
            }
            key = stored.apiKey();
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
     * 保存配置并使验证状态失效(修订号递增)。{@code apiKeyInput}:null 表示
     * 沿用已存密钥,空串表示清空为无密钥(本地服务),非空表示设置新密钥。
     */
    public Status save(String formatCode, String baseUrlInput, String apiKeyInput, String modelInput,
                       String modelSourceInput) {
        return save(formatCode, baseUrlInput, apiKeyInput, modelInput, modelSourceInput, null);
    }

    /**
     * 保存配置并指定显示名称。{@code displayNameInput} 为 null 表示沿用已存名称;
     * 空白则回退到默认标签。
     */
    public Status save(String formatCode, String baseUrlInput, String apiKeyInput, String modelInput,
                       String modelSourceInput, String displayNameInput) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        String model = ProviderUrlSecurity.normalizeModelId(modelInput);
        String modelSource = "MANUAL".equals(modelSourceInput) ? "MANUAL" : "DISCOVERED";
        CustomProviderSettings existing = repository.find().orElse(null);
        // 来源守卫:地址被改到不同来源(scheme/host/有效端口)时,沿用旧密钥
        // 等于把密钥静默交给新服务。必须显式输入新密钥,或显式传空串清空。
        if (apiKeyInput == null && existing != null && existing.apiKey() != null
                && !existing.apiKey().isBlank()
                && (existing.baseUrl() == null
                        || !ProviderUrlSecurity.sameOrigin(normalized, existing.baseUrl()))) {
            throw ModelProviderException.notConfigured("custom",
                    "Base URL points to a different origin; enter the API key for it explicitly "
                            + "or clear the key");
        }
        String displayName;
        if (displayNameInput == null) {
            displayName = existing == null ? null : existing.displayName();
        } else {
            String trimmed = displayNameInput.trim();
            displayName = trimmed.isEmpty() ? null : trimmed;
        }
        // 密钥解析:null 沿用旧密钥与脱敏后缀,空串清空,非空取新密钥并重算后缀
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
        // 配置内容与已存完全一致时直接返回当前状态,不递增修订号(避免无谓地使验证失效)
        if (existing != null && existing.apiFormat().equals(format.name())
                && existing.baseUrl().equals(normalized)
                && equalsNullable(existing.apiKey(), resolvedKey)
                && existing.selectedModel().equals(model)
                && modelSource.equals(existing.modelSource() == null ? "DISCOVERED" : existing.modelSource())
                && equalsNullable(existing.displayName(), displayName)) {
            return status();
        }
        long nextRevision = existing == null ? 1 : existing.configRevision() + 1;
        repository.upsert(new CustomProviderSettings(format.name(), normalized, resolvedKey, masked, model, modelSource,
                displayName, nextRevision, null, existing == null ? now : existing.createdAt(), now, null));
        return status();
    }

    /** 对已保存的配置做兼容性验证。 */
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

    /**
     * 推理端口投影:先取已存配置,再走激活资格检查,失败即拒绝(fail closed)——
     * 等价于网关历史上每次请求执行的 requireStored + requireActivatable 序列。
     */
    @Override
    public RuntimeCustomSettings requireRuntimeSettings() {
        CustomProviderSettings s = requireStored();
        requireActivatable();
        return new RuntimeCustomSettings(s.apiFormat(), s.baseUrl(), s.apiKey(), s.selectedModel());
    }

    /** 供控制器使用的端点预览,避免控制器直接依赖 model 包。 */
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

    /** 标签兜底:历史数据可能缺显示名称,这里保证 UI 契约始终有值。 */
    private static String normalizeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Custom";
        }
        return raw.trim();
    }

    /** 取密钥末 4 位作为脱敏后缀;密钥过短时原样返回(本地服务多为无密钥场景)。 */
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
