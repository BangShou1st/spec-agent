package com.specagent.model.contract;

/**
 * 文件名:ModelProvider.java
 *
 * 用途:模型提供商标识,现在同时充当存储行的"预设类型"。{@code model_providers}
 * 表中的每一行都携带一个预设及自己的凭据与模型选择,因此可以有任意多个提供商。
 * 预设只决定那些用户不应出错的部分:
 *
 * - 固定的 base URL 和请求形态——OpenCode Zen 特意不兼容标准 OpenAI 协议,
 *       所以其传输层保持特殊处理;
 * - 所选模型针对哪个目录做校验;
 * - 设置卡片允许展示哪些可编辑项。
 *
 * 自定义 API 格式仍然不是提供商身份;它由 {@link CustomApiFormat} 承载,
 * 在 Custom 边界内部路由。
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

    /** 用户尚未给提供商命名时使用的默认标签。 */
    public String defaultDisplayName() {
        return defaultDisplayName;
    }

    /** 该行配置的初始 base URL;仅当 {@link #editableBaseUrl()} 为 true 时用户可编辑。 */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** 设置卡片是否允许展示"显示名称"字段。 */
    public boolean userNamed() {
        return userNamed;
    }

    /** 设置卡片是否允许展示 API Format 选择器。 */
    public boolean selectableFormat() {
        return selectableFormat;
    }

    /** 设置卡片是否允许展示 Base URL 字段。 */
    public boolean editableBaseUrl() {
        return editableBaseUrl;
    }

    /** OpenCode Zen / OpenRouter 必须提供凭据才能保存。 */
    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /**
     * 提供商是否自带模型目录:为 true 时设置卡片提供"获取模型/刷新模型"按钮,
     * 而不是手动填写模型 id。
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
