package com.specagent.model.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:OpenCodeModelCatalogTest.java
 *
 * 测试目标:验证 OpenCode 模型目录的列表行为:listFreeModels 只返回以 "-free"
 * 结尾的模型,listAllModels 返回全部模型且去重排序,没有免费模型或空报文时返回空列表。
 */
class OpenCodeModelCatalogTest {

    private static OpenCodeModelCatalog catalogWith(OpenCodeModel... models) {
        OpenCodeZenTransport transport = new OpenCodeZenTransport() {
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
        return new OpenCodeModelCatalog(transport);
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
}
