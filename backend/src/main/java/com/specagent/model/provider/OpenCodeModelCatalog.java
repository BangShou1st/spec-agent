package com.specagent.model.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件名:OpenCodeModelCatalog.java
 *
 * 用途:OpenCode Zen 的动态模型发现。免费模型从实时的 {@code GET /models}
 * 载荷中发现,绝不来自硬编码的生产清单:OpenCode 当前标记为免费的模型都会被
 * 返回,因此提供商增减免费模型无需改代码。
 *
 * 免费标记遵循观察到的线上载荷(与 {@code https://opencode.ai/zen/v1/models}
 * 核对过):免费模型的 id 以 {@code -free} 后缀结尾。
 */
@Component
public class OpenCodeModelCatalog {

    private final OpenCodeZenTransport transport;

    public OpenCodeModelCatalog(OpenCodeZenTransport transport) {
        this.transport = transport;
    }

    /**
     * 返回 OpenCode 当前标记为免费的模型 id 列表。
     *
     * @param apiKey 可选的 bearer 凭据;模型发现是公开接口,可为 null 或空白
     */
    public List<String> listFreeModels(String apiKey) {
        return listAllModels(apiKey).stream().filter(OpenCodeModelCatalog::isFreeModel).toList();
    }

    /**
     * 返回提供商当前暴露的全部模型 id(免费 + 付费),排序后用于稳定展示。
     * 模型可选范围的门禁在设置服务中,不在这里。
     *
     * @param apiKey 可选的 bearer 凭据;模型发现是公开接口,可为 null 或空白
     */
    public List<String> listAllModels(String apiKey) {
        return transport.listModels(apiKey).data().stream()
                .map(OpenCodeModel::id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public static boolean isFreeModel(String modelId) {
        return modelId != null && modelId.endsWith("-free");
    }
}