package com.specagent.modelsettings;

/**
 * 文件名:OpenCodeSettingsStatus.java
 *
 * 用途:OpenCode 设置的服务层状态投影,面向浏览器/API 消费方的安全形状:
 * 只含是否已配置、脱敏密钥与选中模型,密钥原文不出服务层。
 */
public record OpenCodeSettingsStatus(
        boolean configured,
        String maskedKey,
        String selectedModel) {

    public static OpenCodeSettingsStatus unconfigured() {
        return new OpenCodeSettingsStatus(false, null, null);
    }
}
