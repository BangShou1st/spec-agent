package com.specagent.modelsettings;

import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 文件名:OpenCodeSettingsService.java
 *
 * 用途:OpenCode(Zen)提供商设置的应用服务:协调密钥探测与保存,
 * 负责模型发现、仅切换模型与连通性验证,并通过 OpenCodeRuntimeSettingsPort
 * 向推理侧提供运行时设置;支持 database 与外部实测评估两种配置来源,
 * 全程不把工作密钥暴露给 API 层。
 */
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

    /** 保留的默认构造器,供直接调用的单元测试使用。 */
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
                        true, hasKey(settings.apiKey())
                                ? OpenCodeSettingsStatusMask.mask(settings.maskedSuffix()) : null,
                        settings.selectedModel()))
                .orElseGet(OpenCodeSettingsStatus::unconfigured);
    }

    /** 只在内存里探测候选配置;本方法绝不写仓储。 */
    public OpenCodeCandidateModels probe(String apiKey) {
        String candidate = requireKey(apiKey);
        OpenCodeCandidateModels models = currentCandidateModels(candidate);
        // 优先用付费模型验证候选 Key。目录只有免费模型时仍可成功获取模型，
        // 但实际请求按免费模型规则使用 public，因此不会把用户 Key 发给免费档。
        String model = models.recommendedProbeModel();
        String credential = credentialFor(model, candidate, models.freeModels());
        transport.validateCredential(credential, model);
        return models;
    }

    /**
     * 列出当前模型。已配置时沿用已存 Key；首次配置时通过公开模型目录发现，
     * 因而用户可以先选免费模型而不必准备 Key。
     */
    public OpenCodeCandidateModels listModels() {
        return currentCandidateModels(repository.find().map(OpenCodeSettings::apiKey).orElse(null));
    }

    /** 保存前对完整候选配置做全部校验,通过后才落一次 upsert。 */
    public OpenCodeSettingsStatus save(String apiKey, String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            throw new IllegalArgumentException("A model must be selected");
        }
        String model = selectedModel.trim();
        String candidate = keyForSave(apiKey);
        OpenCodeCandidateModels models = currentCandidateModels(candidate);
        if (!models.allModels().contains(model)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(credentialFor(model, candidate, models.freeModels()), model);

        Instant now = Instant.now();
        repository.upsert(new OpenCodeSettings(candidate, suffix(candidate), model, now, now));
        return status();
    }

    /**
     * 只切换选中模型,复用已持久化的密钥。所有提供商校验都在唯一一次
     * upsert 之前完成,因此切换失败时旧配置依然生效,密钥元数据不受影响。
     */
    public OpenCodeSettingsStatus changeModel(String selectedModel) {
        OpenCodeSettings current = requireStoredSettings();
        String model = requireModel(selectedModel);
        OpenCodeCandidateModels models = currentCandidateModels(current.apiKey());
        if (!models.allModels().contains(model)) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(credentialFor(model, current.apiKey(), models.freeModels()), model);

        repository.upsert(new OpenCodeSettings(
                current.apiKey(), current.maskedSuffix(), model,
                current.createdAt(), Instant.now()));
        return status();
    }

    /**
     * 重新验证已持久化的配置,但不写任何东西。与 OpenRouter 的契约一致:
     * 可达性检查针对实际存储的那个模型执行,让设置卡片能提供显式的
     * "再次测试"操作,且永远不会改动已保存的配置对。
     */
    public OpenCodeSettingsStatus validate() {
        OpenCodeSettings current = requireStoredSettings();
        OpenCodeCandidateModels models = currentCandidateModels(current.apiKey());
        if (!models.allModels().contains(current.selectedModel())) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "Selected OpenCode model is not currently available");
        }
        transport.validateCredential(credentialFor(current.selectedModel(), current.apiKey(), models.freeModels()),
                current.selectedModel());
        return status();
    }

    /** 唯一会把完整密钥返回给后端代码的常规服务方法。 */
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
        if (settings.selectedModel() == null || settings.selectedModel().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "OpenCode settings are not configured");
        }
        String credential = credentialForRuntime(settings.selectedModel(), settings.apiKey());
        return new RuntimeOpenCodeSettings(credential, settings.selectedModel(),
                "database:opencode_settings");
    }

    /**
     * 显式的实测评估(live-evaluation)来源。留空的值有意不从产品数据库兜底:
     * evalLive 绝不能继承测试数据库里的提供商行或产品本地的模型选择。
     */
    private RuntimeOpenCodeSettings requireExternalRuntimeSettings() {
        if (externalSelectedModel == null || externalSelectedModel.isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    "Live OpenCode provider configuration is missing: set "
                            + "SPEC_AGENT_EVAL_OPENCODE_MODEL; paid models also require "
                            + "SPEC_AGENT_EVAL_OPENCODE_KEY; test database settings are not used");
        }
        String key = hasKey(externalApiKey) ? externalApiKey.trim() : null;
        String model = externalSelectedModel.trim();
        String credential = credentialForRuntime(model, key);
        return new RuntimeOpenCodeSettings(credential, model,
                EXTERNAL_CREDENTIAL_SOURCE);
    }

    /** 一次实时调用得到完整提供商目录与免费子集。 */
    private OpenCodeCandidateModels currentCandidateModels(String apiKey) {
        // 候选选择列表只含协议已验证为 Chat Completions 的模型:保存/切换都对照
        // 这份列表门禁,因此异协议模型不可能被选中后又在执行时必然失败。
        // 目录与免费子集来自**同一次**上游列举,不再分别拉两遍 /models。
        OpenCodeModelCatalog.OpenCodeCandidateLists lists = catalog.candidateLists(apiKey);
        List<String> allModels = lists.models();
        if (allModels.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode has no currently available models");
        }
        List<String> freeModels = lists.freeModels();
        // 有付费模型时优先验证 Key；如果目录只有免费模型，则用免费模型确认
        // public 请求链路可达，不会把用户 Key 错发给免费档。
        String probeModel = allModels.stream().filter(model -> !freeModels.contains(model))
                .findFirst().orElse(allModels.get(0));
        return new OpenCodeCandidateModels(allModels, freeModels, probeModel);
    }

    /** Model-aware credential resolver used by the native GA broker with cached metadata. */
    public String credentialForModelCached(String model, String apiKey) {
        try {
            return catalog.credentialForModelCached(model, apiKey);
        } catch (IllegalArgumentException missingKey) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    missingKey.getMessage());
        }
    }

    private String credentialForRuntime(String model, String apiKey) {
        try {
            return catalog.credentialForModel(model, apiKey);
        } catch (IllegalArgumentException missingKey) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    missingKey.getMessage());
        }
    }

    private static String credentialFor(String model, String apiKey, List<String> freeModels) {
        try {
            return OpenCodeZenCredentialPolicy.credentialForModel(freeModels.contains(model), apiKey);
        } catch (IllegalArgumentException missingKey) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.NOT_CONFIGURED,
                    missingKey.getMessage());
        }
    }

    private String keyForSave(String requestedKey) {
        if (hasKey(requestedKey)) {
            return requestedKey.trim();
        }
        return repository.find().map(OpenCodeSettings::apiKey).filter(OpenCodeSettingsService::hasKey)
                .orElse(null);
    }

    /** 一次探测/列表的结果:全部暴露的模型、免费子集,以及可达性检查应使用的模型。 */
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

    private static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String suffix(String apiKey) {
        if (!hasKey(apiKey)) {
            return null;
        }
        // 过短的候选密钥绝不能把自己的原文当作脱敏后缀返回。正常 OpenCode
        // 密钥更长,但这里宁可 fail closed(直接不给后缀)。
        return apiKey.length() <= 4 ? "" : apiKey.substring(apiKey.length() - 4);
    }

    private static final class OpenCodeSettingsStatusMask {
        private static String mask(String suffix) {
            return suffix == null || suffix.isBlank() ? "••••" : "••••" + suffix;
        }
    }
}
