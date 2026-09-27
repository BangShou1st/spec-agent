package com.specagent.modelsettings;

import java.time.Instant;

/**
 * 文件名:CustomProviderSettings.java
 *
 * 用途:自定义模型提供商的配置持久化模型,记录 API 格式、Base URL、密钥(含脱敏后缀)、
 * 选中模型、显示名称以及配置版本号(configRevision)与验证版本号(validatedRevision)。
 * 保存或修改配置会使已验证状态失效(通过版本号比对判断),激活前必须重新验证。
 */
public record CustomProviderSettings(
        String apiFormat,
        String baseUrl,
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        String modelSource,
        String displayName,
        long configRevision,
        Long validatedRevision,
        Instant createdAt,
        Instant updatedAt,
        Instant validatedAt) {
}
