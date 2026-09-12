package com.specagent.model.provider;

/** OpenRouter provider policy: fixed base, fixed Chat Completions format. */
public final class OpenRouterGatewaySupport {
    public static final String BASE_URL = "https://openrouter.ai/api/v1";

    private OpenRouterGatewaySupport() {
    }

    /** Free-only policy on ids: exact openrouter/free or suffix :free. */
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
