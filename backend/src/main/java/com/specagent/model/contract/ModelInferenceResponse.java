package com.specagent.model.contract;

/**
 * 文件名:ModelInferenceResponse.java
 *
 * 用途:提供商无关的底层推理结果。只携带补全内容和脱敏后的计量信息——
 * 绝不包含凭据、提供商原始报文或请求头。
 */
public record ModelInferenceResponse(String content,
                                     String finishReason,
                                     Integer promptTokens,
                                     Integer completionTokens) {
}
