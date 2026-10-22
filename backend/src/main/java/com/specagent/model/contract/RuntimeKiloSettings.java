package com.specagent.model.contract;

/**
 * 文件名:RuntimeKiloSettings.java
 *
 * 用途:仅限后端使用的运行时投影,供 Kilo Gateway 推理网关消费(凭据、所选模型、
 * 配置修订与端点)。密钥绝不会出现在 {@code toString()} 或被写进日志/追踪。
 */
public record RuntimeKiloSettings(String apiKey,
                                  String selectedModel,
                                  String credentialSource,
                                  String settingsRevision,
                                  String baseUrl) {

    /** 让既有调用方保持使用规范的产品数据库来源。 */
    public RuntimeKiloSettings(String apiKey, String selectedModel) {
        this(apiKey, selectedModel, "database:kilo_settings", "legacy", null);
    }

    public RuntimeKiloSettings(String apiKey, String selectedModel, String credentialSource) {
        this(apiKey, selectedModel, credentialSource, "legacy", null);
    }

    public RuntimeKiloSettings(String apiKey, String selectedModel,
                               String credentialSource, String settingsRevision) {
        this(apiKey, selectedModel, credentialSource, settingsRevision, null);
    }

    /** 防止意外的诊断日志把提供商密钥暴露出去。 */
    @Override
    public String toString() {
        return "RuntimeKiloSettings[selectedModel=" + selectedModel
                + ", credentialSource=" + credentialSource
                + ", settingsRevision=" + settingsRevision + "]";
    }
}
