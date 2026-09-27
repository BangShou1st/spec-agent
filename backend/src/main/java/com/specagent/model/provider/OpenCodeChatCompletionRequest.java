package com.specagent.model.provider;

import java.util.List;
import java.util.Map;

/**
 * 文件名:OpenCodeChatCompletionRequest.java
 *
 * 用途:发给 OpenCode Zen 的最小化 chat completion 载荷。
 *
 * 生产补全请求使用已验证的 OpenCode 客户端所要求的 OpenAI 兼容流式形态。
 * 仅存在于线上的字段由传输层负责;本 DTO 只携带模型名、消息列表,以及由
 * 提供商无关输出契约翻译而来的可选提供商原生格式 map。格式为 null 时保持
 * 与历史文本形态逐字节一致。生产任务类型不携带任务专属的生成上限。
 */
public record OpenCodeChatCompletionRequest(
        String model,
        List<OpenCodeChatMessage> messages,
        Map<String, Object> responseFormat) {

    public OpenCodeChatCompletionRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        responseFormat = responseFormat == null || responseFormat.isEmpty()
                ? null
                : Map.copyOf(responseFormat);
    }

    /**
     * 历史构造形态:不对提供商做任何格式强制。
     */
    public OpenCodeChatCompletionRequest(String model, List<OpenCodeChatMessage> messages) {
        this(model, messages, null);
    }
}
