package com.specagent.model.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenRouterFreeFilterTest {
    @Test void freePolicy() {
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openrouter/free")).isTrue();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("meta-llama/llama-3:free")).isTrue();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openai/gpt-4o")).isFalse();
        // Display-name-contains-free must not qualify: only id suffix counts.
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openai/gpt-4o-free-display")).isFalse();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("my-free-model")).isFalse();
        assertThat(OpenRouterGatewaySupport.isFreeModelId(null)).isFalse();
    }
}
