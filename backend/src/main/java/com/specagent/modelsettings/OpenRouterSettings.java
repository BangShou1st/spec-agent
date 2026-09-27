package com.specagent.modelsettings;

import java.time.Instant;

/**
 * 文件名:OpenRouterSettings.java
 *
 * 用途:OpenRouter 提供商的全局持久化配置:API 密钥(含脱敏后缀)、选中模型、
 * 配置修订号(configRevision)与验证修订号(validatedRevision)。配置变更会
 * 递增修订号并使验证失效,激活前必须重新验证。
 */
public record OpenRouterSettings(
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        long configRevision,
        Long validatedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {
}
