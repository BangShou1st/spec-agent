package com.specagent.model.contract;

/**
 * 文件名:RuntimeOpenCodeSettings.java
 *
 * 用途:仅限后端使用的运行时投影,供生产模型网关消费(凭据、所选模型、
 * 凭据来源)。
 */
public record RuntimeOpenCodeSettings(String apiKey,
                                       String selectedModel,
                                       String credentialSource) {

    /** 让既有调用方保持使用规范的产品数据库来源。 */
    public RuntimeOpenCodeSettings(String apiKey, String selectedModel) {
        this(apiKey, selectedModel, "database:opencode_settings");
    }

    /** 防止意外的诊断日志把提供商密钥暴露出去。 */
    @Override
    public String toString() {
        return "RuntimeOpenCodeSettings[selectedModel=" + selectedModel
                + ", credentialSource=" + credentialSource + "]";
    }
}
