package com.specagent.model.contract;

/**
 * Provider identity, now acting as the <em>preset kind</em> of a stored row.
 *
 * <p>A row in {@code model_providers} carries a preset plus its own credential
 * and model selection, so there can be any number of providers. The preset
 * only decides the parts the user must not be able to get wrong:
 *
 * <ul>
 *   <li>the fixed base URL and request shape — OpenCode Zen is deliberately
 *       NOT plain OpenAI-compatible, so its transport stays special-cased;</li>
 *   <li>which catalog a selected model is validated against;</li>
 *   <li>which edit affordances the settings card may show.</li>
 * </ul>
 *
 * <p>Custom API format is still NOT a provider identity; it is carried by
 * {@link CustomApiFormat} and routed inside the Custom boundary.
 */
public enum ModelProvider {

    OPENCODE_ZEN("OpenCode Zen", "https://opencode.ai/zen/v1",
            false, false, false, true, true),

    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1",
            false, false, false, true, true),

    CUSTOM("Custom", "",
            true, true, true, false, false);

    private final String defaultDisplayName;
    private final String defaultBaseUrl;
    private final boolean userNamed;
    private final boolean selectableFormat;
    private final boolean editableBaseUrl;
    private final boolean requiresApiKey;
    private final boolean builtInCatalog;

    ModelProvider(String defaultDisplayName, String defaultBaseUrl, boolean userNamed,
                  boolean selectableFormat, boolean editableBaseUrl, boolean requiresApiKey,
                  boolean builtInCatalog) {
        this.defaultDisplayName = defaultDisplayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.userNamed = userNamed;
        this.selectableFormat = selectableFormat;
        this.editableBaseUrl = editableBaseUrl;
        this.requiresApiKey = requiresApiKey;
        this.builtInCatalog = builtInCatalog;
    }

    /** Label used when the user has not named the provider yet. */
    public String defaultDisplayName() {
        return defaultDisplayName;
    }

    /** Base URL the row starts from; user-editable only when {@link #editableBaseUrl()}. */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** Whether the settings card may expose a 显示名称 field. */
    public boolean userNamed() {
        return userNamed;
    }

    /** Whether the settings card may expose an API Format selector. */
    public boolean selectableFormat() {
        return selectableFormat;
    }

    /** Whether the settings card may expose a Base URL field. */
    public boolean editableBaseUrl() {
        return editableBaseUrl;
    }

    /** OpenCode Zen / OpenRouter cannot be saved without a credential. */
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /**
     * True when the provider publishes its own model catalog, so the card
     * offers 获取模型/刷新模型 instead of a manual model id.
     */
    public boolean builtInCatalog() {
        return builtInCatalog;
    }

    public static ModelProvider fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("model provider is required");
        }
        return switch (code.trim().toUpperCase()) {
            case "OPENCODE_ZEN" -> OPENCODE_ZEN;
            case "OPENROUTER" -> OPENROUTER;
            case "CUSTOM" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown model provider: " + code);
        };
    }
}
