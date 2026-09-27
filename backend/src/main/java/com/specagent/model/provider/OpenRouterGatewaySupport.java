package com.specagent.model.provider;

/**
 * 文件名:OpenRouterGatewaySupport.java
 *
 * 用途:OpenRouter 提供商策略:固定的 base URL、固定的 Chat Completions 格式,
 * 以及免费模型的 id 判定规则。
 */
public final class OpenRouterGatewaySupport {
    public static final String BASE_URL = "https://openrouter.ai/api/v1";

    private OpenRouterGatewaySupport() {
    }

    /** 免费 id 的判定规则:精确匹配 openrouter/free,或以 :free 后缀结尾。 */
    public static boolean isFreeModelId(String id) {
        if (id == null) {
            return false;
        }
        String v = id.trim();
        if (v.isEmpty()) {
            return false;
        }
        return v.equals("openrouter/free") || v.endsWith(":free");
    }
}
