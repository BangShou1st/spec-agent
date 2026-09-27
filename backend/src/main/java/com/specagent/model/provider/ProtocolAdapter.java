package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import java.util.List;
import java.util.Map;

/**
 * 文件名:ProtocolAdapter.java
 *
 * 用途:单一 API 格式的高内聚线上格式翻译接口。输入输出都是提供商无关的。
 * 绝不执行 Agent 工具、绝不访问数据库、也绝不感知 OPENROUTER / CUSTOM 路由。
 */
public interface ProtocolAdapter {

    CustomApiFormat format();

    /** 提供商无关请求 -> 线上请求体。绝不包含凭据。 */
    Map<String, Object> buildRequestBody(ModelInferenceRequest request, String model);

    /** 流式变体只是加上 {@code stream=true},语义不变。 */
    default Map<String, Object> buildStreamRequestBody(ModelInferenceRequest request, String model) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(buildRequestBody(request, model));
        body.put("stream", true);
        // Anthropic 与 Chat/Responses 都使用 stream:true。
        return body;
    }

    /** 授权相关的线上头。密钥缺失时返回空(本地免认证服务)。 */
    Map<String, String> authHeaders(String apiKey);

    /** 非流式响应提取。白名单:只放行最终文本。 */
    ModelInferenceResponse parseNonStreamResponse(JsonNode root, String context);

    /**
     * 把 SSE {@code data:} 载荷转换为可见文本。事件不携带助手展示文本时
     * (reasoning、usage、生命周期事件)返回 null。遇到协议错误事件时抛出
     * {@link ModelProviderException}。
     */
    String extractVisibleText(JsonNode data, String context);

    /** 该 SSE 数据事件是否终止流。 */
    boolean isTerminalData(String rawData, JsonNode data, String context);

    /**
     * 该 SSE 数据事件是否是协议认可的成功终止。只有出现这类事件,流才算成功;
     * TCP/HTTP 层正常结束但只见文本而没有成功终止事件的,视为被截断的流,
     * 必须按失败处理。
     */
    default boolean isSuccessfulTerminal(String rawData, JsonNode data, String context) {
        return isTerminalData(rawData, data, context) && !isFailureTerminal(data, context);
    }

    /**
     * 该 SSE 数据事件是否是协议认可的失败终止(提供商错误、补全不完整、取消)。
     * 绝不把累积的部分文本当作成功返回。
     */
    default boolean isFailureTerminal(JsonNode data, String context) {
        return false;
    }

    /** 解析 {@code GET <base>/models} 载荷为候选模型 id 列表。 */
    List<String> parseModelList(JsonNode root, String context);

    /** 把 HTTP 错误状态码 + 可选响应体映射为提供商无关的失败。 */
    ModelProviderException mapHttpError(int httpStatus, String bodySnippet, String context);
}
