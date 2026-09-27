package com.specagent.model.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:OpenRouterFreeFilterTest.java
 *
 * 测试目标:验证 OpenRouter 免费模型的判定策略:只有模型 id 以 ":free" 后缀结尾
 * 才算免费模型;显示名包含 "free" 或 id 中间出现 free 等情况一律不算,null 返回 false。
 */
class OpenRouterFreeFilterTest {
    @Test void freePolicy() {
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openrouter/free")).isTrue();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("meta-llama/llama-3:free")).isTrue();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openai/gpt-4o")).isFalse();
        // 显示名包含 "free" 不能算免费:只认 id 后缀。
        assertThat(OpenRouterGatewaySupport.isFreeModelId("openai/gpt-4o-free-display")).isFalse();
        assertThat(OpenRouterGatewaySupport.isFreeModelId("my-free-model")).isFalse();
        assertThat(OpenRouterGatewaySupport.isFreeModelId(null)).isFalse();
    }
}
