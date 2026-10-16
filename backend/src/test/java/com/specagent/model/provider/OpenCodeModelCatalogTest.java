package com.specagent.model.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:OpenCodeModelCatalogTest.java
 *
 * 测试目标:验证 OpenCode 模型目录的判定行为。
 *
 * <ul>
 *   <li>无元数据来源时退回已验证的保守规则:{@code listFreeModels} 只返回以
 *       {@code -free} 结尾的模型;{@code listAllModels} 返回全部模型且去重排序。</li>
 *   <li>有元数据来源时按官方价格/协议/下线事实判定:免费看 input=0 且 output=0
 *       (因此没有 {@code -free} 后缀的免费模型也会被发现,名字带 {@code -free}
 *       的付费模型不会被放行);可执行集合只含协议为 Chat Completions 的模型;
 *       已下线模型被排除;元数据不可用时不假设任何模型免费。</li>
 * </ul>
 */
class OpenCodeModelCatalogTest {

    private static OpenCodeZenTransport transportWith(OpenCodeModel... models) {
        return new OpenCodeZenTransport() {
            @Override
            public OpenCodeCompletionResponse complete(String apiKey, String sessionId,
                                                       OpenCodeChatCompletionRequest request) {
                throw new UnsupportedOperationException("catalog test does not complete");
            }

            @Override
            public OpenCodeModelList listModels(String apiKey) {
                return new OpenCodeModelList(List.of(models));
            }

            @Override
            public void validateCredential(String apiKey, String model) {
                throw new UnsupportedOperationException("catalog test does not probe");
            }
        };
    }

    private static OpenCodeModelCatalog catalogWith(OpenCodeModel... models) {
        return new OpenCodeModelCatalog(transportWith(models));
    }

    /** 元数据视图:available 为 false 表示目录元数据本次不可用。 */
    private static OpenCodeZenModelMetadataSource sourceOf(
            boolean available, OpenCodeZenModelMetadata... entries) {
        java.util.Map<String, OpenCodeZenModelMetadata> map = new java.util.LinkedHashMap<>();
        for (OpenCodeZenModelMetadata entry : entries) {
            map.put(entry.modelId(), entry);
        }
        return () -> new OpenCodeZenModelMetadataSource.View(available, map);
    }

    private static OpenCodeZenModelMetadata meta(String id, boolean free, boolean retired,
                                                 OpenCodeModelProtocol protocol) {
        return new OpenCodeZenModelMetadata(id, free, retired, protocol);
    }

    @Test
    void listFreeModelsReturnsOnlyFreeSuffixedModels() {
        // 模拟线上 OpenCode /models 报文形态:data 条目带 id;
        // 免费模型以 "-free" 后缀暴露。
        OpenCodeModelCatalog catalog = catalogWith(
                new OpenCodeModel("paid-model", "opencode"),
                new OpenCodeModel("one-free", "opencode"),
                new OpenCodeModel("two-free", "opencode"));

        List<String> free = catalog.listFreeModels(null);

        assertThat(free).containsExactly("one-free", "two-free");
        assertThat(free).doesNotContain("paid-model");
    }

    @Test
    void listAllModelsReturnsEveryExposedModelSortedAndDistinct() {
        OpenCodeModelCatalog catalog = catalogWith(
                new OpenCodeModel("paid-model", "opencode"),
                new OpenCodeModel("two-free", "opencode"),
                new OpenCodeModel("one-free", "opencode"),
                new OpenCodeModel("paid-model", "opencode"));

        assertThat(catalog.listAllModels(null))
                .containsExactly("one-free", "paid-model", "two-free");
        assertThat(catalog.listAllModels(null)).anySatisfy(id -> assertThat(id).doesNotEndWith("-free"));
    }

    @Test
    void listFreeModelsReturnsEmptyWhenNothingIsFree() {
        OpenCodeModelCatalog catalog = catalogWith(
                new OpenCodeModel("paid-model", "opencode"),
                new OpenCodeModel("another-paid", "opencode"));

        assertThat(catalog.listFreeModels(null)).isEmpty();
    }

    @Test
    void listFreeModelsReturnsEmptyForEmptyPayload() {
        OpenCodeModelCatalog catalog = catalogWith();

        assertThat(catalog.listFreeModels(null)).isEmpty();
    }

    // ------------------------------------------------- 官方元数据事实(真实端点已核对)

    @Test
    void priceMetadataFindsFreeModelsWithoutTheFreeSuffix() {
        // big-pickle:官方元数据 input=0/output=0 且无 "-free" 后缀。
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("big-pickle", "opencode")),
                sourceOf(true, meta("big-pickle", true, false, OpenCodeModelProtocol.CHAT)));

        assertThat(catalog.listFreeModels(null)).containsExactly("big-pickle");
        assertThat(catalog.listAllModels(null)).containsExactly("big-pickle");
    }

    @Test
    void pricedModelNamedFreeIsNotTreatedAsFree() {
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("really-paid-free", "opencode")),
                sourceOf(true, meta("really-paid-free", false, false, OpenCodeModelProtocol.CHAT)));

        assertThat(catalog.listFreeModels(null)).isEmpty();
    }

    @Test
    void modelCredentialUsesPublicForPricedFreeAndKeyForPaid() {
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("big-pickle", "opencode"),
                        new OpenCodeModel("deepseek-v4-pro", "opencode")),
                sourceOf(true,
                        meta("big-pickle", true, false, OpenCodeModelProtocol.CHAT),
                        meta("deepseek-v4-pro", false, false, OpenCodeModelProtocol.CHAT)));

        assertThat(catalog.credentialForModel("big-pickle", "stored-paid-key")).isEqualTo("public");
        assertThat(catalog.credentialForModel("deepseek-v4-pro", "paid-key")).isEqualTo("paid-key");
        assertThatThrownBy(() -> catalog.credentialForModel("deepseek-v4-pro", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required for paid");
    }

    @Test
    void modelMissingFromAvailableCatalogIsNotGivenPublicCredential() {
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("unregistered", "opencode")),
                sourceOf(true));

        assertThatThrownBy(() -> catalog.credentialForModel("unregistered", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required for paid");
    }

    @Test
    void responsesProtocolModelIsFreeButNotSelectable() {
        // muse-spark-1.3-contributor-free:免费,但 provider.npm=@ai-sdk/openai(Responses)。
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("muse-spark-1.3-contributor-free", "opencode")),
                sourceOf(true, meta("muse-spark-1.3-contributor-free", true, false,
                        OpenCodeModelProtocol.RESPONSES)));

        assertThat(catalog.listFreeModels(null)).isEmpty();
        assertThat(catalog.listAvailableModels(null)).isEmpty();
        assertThat(catalog.protocolOf("muse-spark-1.3-contributor-free"))
                .isEqualTo(OpenCodeModelProtocol.RESPONSES);
    }

    @Test
    void retiredModelIsExcludedEvenWhenFree() {
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("muse-spark-1.2-contributor-free", "opencode")),
                sourceOf(true, meta("muse-spark-1.2-contributor-free", true, true,
                        OpenCodeModelProtocol.RESPONSES)));

        assertThat(catalog.listFreeModels(null)).isEmpty();
        assertThat(catalog.listAvailableModels(null)).isEmpty();
    }

    @Test
    void modelUnknownToAvailableMetadataIsNotSelectable() {
        // 只有元数据已登记的模型才算"已验证可执行";未登记 = 未验证。
        OpenCodeModelCatalog catalog = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("test", "opencode"),
                        new OpenCodeModel("cat-jam-free", "opencode")),
                sourceOf(true, meta("cat-jam-free", true, false, OpenCodeModelProtocol.CHAT)));

        assertThat(catalog.listAvailableModels(null)).containsExactly("cat-jam-free");
        assertThat(catalog.listFreeModels(null)).containsExactly("cat-jam-free");
        assertThat(catalog.protocolOf("test")).isEqualTo(OpenCodeModelProtocol.UNKNOWN);
    }

    @Test
    void unavailableMetadataNeverAssumesModelsAreFreeButKeepsTheVerifiedCallChainUsable() {
        OpenCodeModelCatalog unavailable = new OpenCodeModelCatalog(
                transportWith(new OpenCodeModel("big-pickle", "opencode"),
                        new OpenCodeModel("cat-jam-free", "opencode")),
                () -> OpenCodeZenModelMetadataSource.View.unavailable());

        // 没有价格事实就绝不假设免费:退回既有 "-free" 后缀规则。
        assertThat(unavailable.listFreeModels(null)).containsExactly("cat-jam-free");
        assertThat(unavailable.isFreeModel("big-pickle")).isFalse();
        // 协议无法判定时不新增误判:既有可用列表保持可用。
        assertThat(unavailable.listAvailableModels(null)).containsExactly("big-pickle", "cat-jam-free");
        assertThat(unavailable.protocolOf("cat-jam-free")).isEqualTo(OpenCodeModelProtocol.UNKNOWN);
    }
}
