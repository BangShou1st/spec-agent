package com.specagent.modelsettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.CustomApiFormat;
import com.specagent.model.provider.ModelProviderException;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelErrorCategory;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenRouterGatewaySupport;
import com.specagent.model.provider.OpenRouterModelQualification;
import com.specagent.model.provider.ProtocolAdapter;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ProviderHttpSupport;
import com.specagent.model.provider.ProviderUrlSecurity;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 文件名:ProviderModelCatalogService.java
 *
 * 用途:模型目录发现服务,每种预设类型走一条专属代码路径。这是刻意设计的
 * 唯一知道"某预设如何暴露其目录"的地方:OpenCode Zen 与 OpenRouter 通过各自的
 * 特殊传输层发布经过资格筛选的模型列表;Custom 网关则通过协商出的协议适配器
 * 探测,不支持 {@code /models} 时退化为手动填写模型。
 */
@Service
public class ProviderModelCatalogService {

    private final OpenCodeModelCatalog openCodeCatalog;
    private final ObjectMapper mapper;
    private final ProtocolAdapterRegistry registry;
    private final HttpClient client;

    public ProviderModelCatalogService(OpenCodeModelCatalog openCodeCatalog, ObjectMapper mapper,
                                       ProtocolAdapterRegistry registry) {
        this.openCodeCatalog = openCodeCatalog;
        this.mapper = mapper;
        this.registry = registry;
        this.client = ProviderHttpSupport.newClient(Duration.ofSeconds(10));
    }

    /**
     * 一次发现的结果。{@code manualModel} 为 true 表示网关不提供模型列表,
     * UI 必须退回到手动输入模型 id。
     */
    public record Discovery(List<String> allModels, List<String> freeModels,
                            boolean manualModel, String endpointPreview) {
    }

    /**
     * 探测一个未保存的候选配置。草稿值优先于已存值,
     * 这样卡片可以在保存之前先测试某个 Base URL / 协议。
     */
    public Discovery probe(String presetCode, String draftApiFormat, String draftBaseUrl,
                           String apiKey, ModelProviderRecord stored) {
        return switch (com.specagent.model.contract.ModelProvider.fromCode(presetCode)) {
            case OPENCODE_ZEN -> discoverOpenCode(apiKey);
            case OPENROUTER -> discoverOpenRouter(apiKey);
            case CUSTOM -> discoverCustom(draftApiFormat, draftBaseUrl, apiKey, stored);
        };
    }

    /** 使用已存密钥列出模型目录。 */
    public Discovery list(ModelProviderRecord record) {
        return switch (record.preset()) {
            case OPENCODE_ZEN -> discoverOpenCode(record.apiKey());
            case OPENROUTER -> discoverOpenRouter(record.apiKey());
            case CUSTOM -> discoverCustom(record.apiFormat(), record.baseUrl(), null, record);
        };
    }

    private Discovery discoverOpenCode(String apiKey) {
        List<String> all = openCodeCatalog.listAllModels(apiKey);
        if (all.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_MODEL,
                    "OpenCode has no currently available models");
        }
        List<String> free = all.stream().filter(OpenCodeModelCatalog::isFreeModel).toList();
        return new Discovery(all, free, false, null);
    }

    private Discovery discoverOpenRouter(String apiKey) {
        var ids = OpenRouterModelQualification.qualifiedModelIds(
                fetchModelRoot(apiKey, "openrouter"), "openrouter");
        List<String> free = ids.all().stream()
                .filter(OpenRouterGatewaySupport::isFreeModelId)
                .toList();
        return new Discovery(ids.all(), free, false, null);
    }

    /**
     * Custom 网关协商自己的协议,因此发现走适配器注册表;
     * 404/405/501 一律退化为手动填写模型。{@code apiKey == null} 复用已存密钥;
     * 空串表示无鉴权。
     *
     * 来源边界:已存密钥只允许在同一来源(scheme/host/有效端口)上复用。
     * 用户把 base URL 指向新服务时必须显式输入该服务自己的凭据——绝不把
     * 旧服务的密钥自动带到新来源的第一个请求上。
     */
    private Discovery discoverCustom(String formatCode, String baseUrlInput, String apiKey,
                                     ModelProviderRecord stored) {
        CustomApiFormat format = CustomApiFormat.fromCode(formatCode);
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(baseUrlInput);
        String key;
        if (apiKey != null && !apiKey.isBlank()) {
            key = apiKey.trim();
        } else if (apiKey == null && stored != null && stored.hasKey()) {
            requireSameOriginForReuse(normalized, stored);
            key = stored.apiKey();
        } else {
            key = null;
        }
        ProtocolAdapter adapter = registry.require(format);
        String context = "custom-" + format.name().toLowerCase();
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, normalized + "/models", adapter.authHeaders(key),
                ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        String preview = previewEndpoint(formatCode, normalized);
        if (result.status() == 404 || result.status() == 405 || result.status() == 501 || result.json() == null) {
            return new Discovery(List.of(), List.of(), true, preview);
        }
        List<String> ids = adapter.parseModelList(result.json(), context);
        return new Discovery(ids, ids, false, preview);
    }

    /**
     * 凭据复用的来源守卫:目标 URL 与已存配置的规范化来源不一致时拒绝
     * 自动携带旧密钥,要求显式输入新凭据(或显式清空为无鉴权)。
     */
    static void requireSameOriginForReuse(String normalizedTarget, ModelProviderRecord stored) {
        if (stored.baseUrl() == null
                || !ProviderUrlSecurity.sameOrigin(normalizedTarget, stored.baseUrl())) {
            throw ModelProviderException.notConfigured("custom",
                    "Base URL points to a different origin; enter the API key for it explicitly");
        }
    }

    /** 仅供展示的端点规范化预览;输入不合法时返回 null 而不抛异常。 */
    public String previewEndpoint(String formatCode, String baseUrlInput) {
        try {
            return ProviderUrlSecurity.canonicalEndpoint(
                    ProviderUrlSecurity.normalizeBaseUrl(baseUrlInput),
                    CustomApiFormat.fromCode(formatCode));
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode fetchModelRoot(String apiKey, String context) {
        var adapter = registry.require(CustomApiFormat.CHAT_COMPLETIONS);
        ProviderHttpSupport.HttpResult result = ProviderHttpSupport.getJson(
                client, mapper, OpenRouterGatewaySupport.BASE_URL + "/models",
                adapter.authHeaders(apiKey), ProviderHttpSupport.SETTINGS_TIMEOUT, context);
        JsonNode root = result.json();
        if (root == null) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list unsupported");
        }
        return root;
    }
}
