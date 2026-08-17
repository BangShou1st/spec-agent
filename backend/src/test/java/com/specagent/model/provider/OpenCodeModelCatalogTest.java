package com.specagent.model.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeModelCatalogTest {

    private static OpenCodeModelCatalog catalogWith(OpenCodeModel... models) {
        OpenCodeZenTransport transport = new OpenCodeZenTransport() {
            @Override
            public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
                throw new UnsupportedOperationException("catalog test does not complete");
            }

            @Override
            public OpenCodeModelList listModels(String apiKey) {
                return new OpenCodeModelList(List.of(models));
            }

            @Override
            public void validateCredential(String apiKey) {
                throw new UnsupportedOperationException("catalog test does not probe");
            }
        };
        return new OpenCodeModelCatalog(transport);
    }

    @Test
    void listFreeModelsReturnsOnlyFreeSuffixedModels() {
        // Mirrors the live OpenCode /models payload shape: data entries with an
        // id; free models are exposed with a trailing "-free".
        OpenCodeModelCatalog catalog = catalogWith(
                new OpenCodeModel("paid-model", "opencode"),
                new OpenCodeModel("one-free", "opencode"),
                new OpenCodeModel("two-free", "opencode"));

        List<String> free = catalog.listFreeModels(null);

        assertThat(free).containsExactly("one-free", "two-free");
        assertThat(free).doesNotContain("paid-model");
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