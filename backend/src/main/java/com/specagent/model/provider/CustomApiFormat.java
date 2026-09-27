package com.specagent.model.provider;

/**
 * 文件名:CustomApiFormat.java
 *
 * 用途:自定义提供商使用的线上协议格式。提供商身份仍是 {@link ModelProvider#CUSTOM};
 * 本枚举只在 Custom 边界内部选择对应的协议适配器。
 */
public enum CustomApiFormat {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES;

    public static CustomApiFormat fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("api format is required");
        }
        return switch (code.trim().toUpperCase()) {
            case "CHAT_COMPLETIONS" -> CHAT_COMPLETIONS;
            case "RESPONSES" -> RESPONSES;
            case "ANTHROPIC_MESSAGES" -> ANTHROPIC_MESSAGES;
            default -> throw new IllegalArgumentException("Unknown api format: " + code);
        };
    }

    /** 拼接到规范化 base URL 之后的规范端点后缀。 */
    public String endpointSuffix() {
        return switch (this) {
            case CHAT_COMPLETIONS -> "/chat/completions";
            case RESPONSES -> "/responses";
            case ANTHROPIC_MESSAGES -> "/messages";
        };
    }

    /** 冻结设计要求的 UI 展示标签。 */
    public String presentationLabel() {
        return switch (this) {
            case ANTHROPIC_MESSAGES -> "Anthropic Messages (/v1/messages)";
            case CHAT_COMPLETIONS -> "Chat Completions (/chat/completions)";
            case RESPONSES -> "Responses (/responses)";
        };
    }
}
