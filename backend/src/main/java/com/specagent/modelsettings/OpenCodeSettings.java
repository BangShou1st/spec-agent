package com.specagent.modelsettings;

import java.time.Instant;

/**
 * 文件名:OpenCodeSettings.java
 *
 * 用途:OpenCode(Zen)提供商的全局持久化配置:API 密钥(含脱敏后缀)
 * 与选中的模型 id,单行存储。
 */
public record OpenCodeSettings(
        String apiKey,
        String maskedSuffix,
        String selectedModel,
        Instant createdAt,
        Instant updatedAt) {
}
