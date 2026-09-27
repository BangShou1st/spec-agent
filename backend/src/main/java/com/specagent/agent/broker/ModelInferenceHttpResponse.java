package com.specagent.agent.broker;

/**
 * 文件名:ModelInferenceHttpResponse.java
 *
 * 用途:内部模型推理 broker 的响应 wire DTO。只携带补全内容与脱敏的
 * 计量信息(token 用量)——provider 密钥绝不会出现在这里或任何日志行中。
 *
 * 协作:由 InternalModelInferenceController 返回给 Python Brain。
 */
public record ModelInferenceHttpResponse(String protocolVersion,
                                         String content,
                                         String finishReason,
                                         Usage usage) {

    public record Usage(Integer promptTokens, Integer completionTokens) {
    }
}
