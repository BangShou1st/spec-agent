package com.specagent.modelsettings;

import com.specagent.modelsettings.OpenCodeSettingsStatus;

/**
 * 文件名:OpenCodeSettingsResponse.java
 *
 * 用途:OpenCode 设置状态的安全响应投影:是否已配置、脱敏后的密钥、
 * 选中的模型,不含任何可用于调用提供商的敏感信息。
 */
public record OpenCodeSettingsResponse(boolean configured, String maskedKey, String selectedModel) {

    public static OpenCodeSettingsResponse from(OpenCodeSettingsStatus status) {
        return new OpenCodeSettingsResponse(status.configured(), status.maskedKey(), status.selectedModel());
    }
}
