package com.specagent.agent.broker;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ModelInferenceHttpRequest.java
 *
 * 用途:内部模型推理 broker 的请求 wire DTO(Python → Spring)。
 * 严格解析:未知字段与未知协议版本一律拒绝。broker 绝不转发任意
 * header,也绝不回显任何凭据。
 *
 * 协作:由 Python Brain 构造发送,经 InternalModelInferenceController
 * 校验后映射为 ModelInferenceRequest。
 */
public record ModelInferenceHttpRequest(String protocolVersion,
                                        UUID runId,
                                        String callType,
                                        List<Message> messages,
                                        Integer maxOutputTokens) {

    public ModelInferenceHttpRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public record Message(String role, String content) {
    }
}
