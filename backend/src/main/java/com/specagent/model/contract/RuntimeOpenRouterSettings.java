package com.specagent.model.contract;

/**
 * 文件名:RuntimeOpenRouterSettings.java
 *
 * 用途:推理网关使用的 OpenRouter 存储配置的不可变运行时投影。只携带一次请求
 * 所需的字段;提供商密钥绝不出现在诊断信息中。
 */
public record RuntimeOpenRouterSettings(String apiKey,
                                         String selectedModel) {

    /** 防止意外的诊断日志把提供商密钥暴露出去。 */
    @Override
    public String toString() {
        return "RuntimeOpenRouterSettings[selectedModel=" + selectedModel + "]";
    }
}
