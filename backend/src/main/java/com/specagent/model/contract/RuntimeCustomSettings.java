package com.specagent.model.contract;

/**
 * 文件名:RuntimeCustomSettings.java
 *
 * 用途:推理网关使用的自定义提供商配置的不可变运行时投影:显式 API 格式、
 * 规范化后的 base URL、所选模型与密钥。提供商密钥绝不出现在诊断信息中。
 */
public record RuntimeCustomSettings(String apiFormat,
                                     String baseUrl,
                                     String apiKey,
                                     String selectedModel) {

    /** 防止意外的诊断日志把提供商密钥暴露出去。 */
    @Override
    public String toString() {
        return "RuntimeCustomSettings[apiFormat=" + apiFormat
                + ", baseUrl=" + baseUrl
                + ", selectedModel=" + selectedModel + "]";
    }
}
