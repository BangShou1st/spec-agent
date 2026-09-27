package com.specagent.modelsettings;

import com.specagent.model.provider.CompatibilityProbeService;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 文件名:ModelProvidersService.java
 *
 * 用途:"每个提供商一行"的管理服务:用户新建提供商就是插入一行,
 * 设置页支持"再多一个网关"无需改代码。预设行由迁移种子生成,并通过
 * {@link ModelProvider} 保持可区分,使 OpenCode Zen 的直连请求形状与
 * OpenRouter 的资格验证流程得以特殊处理。
 */
@Service
public class ModelProvidersService {

    private static final Set<String> FORMATS = Set.of(
            "CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC_MESSAGES");

    private final ModelProviderRepository repository;
    private final ProviderModelCatalogService catalog;
    private final CompatibilityProbeService probe;
    private final ModelProviderSettingsService active;

    public ModelProvidersService(ModelProviderRepository repository,
                                 ProviderModelCatalogService catalog,
                                 CompatibilityProbeService probe,
                                 ModelProviderSettingsService active) {
        this.repository = repository;
        this.catalog = catalog;
        this.probe = probe;
        this.active = active;
    }

    /**
     * 部分更新载荷。{@code null} 表示"保留已存值",这样设置弹窗只需提交
     * 用户改动过的字段;空串 {@code apiKey} 表示显式清空密钥。
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
     * 快速创建:只允许创建用户自建提供商。预设是种子行,其 Base URL 和
     * 请求形状由后端固定,不接受通过 API 修改。
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
                // 预设行由迁移种子生成,永远不会通过 API 创建
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
     * 应用补丁更新。密钥、协议或 Base URL 的任何变更都会递增修订号并
     * 作废之前的验证结果,确保激活永远不会依赖一份已不能描述当前配置的测试。
     */
    public ModelProviderRecord update(UUID id, Patch patch) {
        ModelProviderRecord current = require(id);
        String displayName = patch.displayName() == null
                ? current.displayName() : requireDisplayName(patch.displayName());
        String apiFormat = patch.apiFormat() == null
                ? current.apiFormat() : requireFormat(patch.apiFormat());
        String baseUrl = patch.baseUrl() == null
                ? current.baseUrl() : ProviderUrlSecurity.validateAndNormalizeBaseUrl(patch.baseUrl());
        // 来源守卫:地址被改到不同来源(scheme/host/有效端口)时,沿用旧密钥
        // 等于把密钥静默交给新服务。必须显式输入新密钥,或显式传空串清空。
        if (patch.apiKey() == null && current.hasKey()
                && !ProviderUrlSecurity.sameOrigin(baseUrl, current.baseUrl())) {
            throw ModelProviderException.notConfigured("custom",
                    "Base URL points to a different origin; enter the API key for it explicitly "
                            + "or clear the key");
        }
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

    /** 针对未保存编辑的草稿发现;已存行提供兜底密钥。 */
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

    /** 使用已存密钥查询模型目录;不会要求重新提供密钥。 */
    public ProviderModelCatalogService.Discovery listModels(UUID id) {
        return catalog.list(require(id));
    }

    /**
     * 对已存的配置对做连通性测试。失败只上报、不改动已存配置,
     * 因此一个本来能用的提供商不会因验证失败而坏掉。
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

    /** 把一个已配置的行标记为新运行的激活模型目标。 */
    public void activate(ModelProviderRecord record) {
        active.setActiveTarget(record.preset().name(), record.id());
    }

    /** 激活门禁:该行必须已配置,且在当前修订号下通过过验证。 */
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
        // 取末 4 位作为脱敏后缀;密钥本身不外泄
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }
}
