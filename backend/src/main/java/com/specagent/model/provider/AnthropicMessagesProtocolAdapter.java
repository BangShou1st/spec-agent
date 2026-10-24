package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 文件名:AnthropicMessagesProtocolAdapter.java
 *
 * 用途:Anthropic Messages 协议的线上格式适配器({@code POST <base>/messages})。
 * 把提供商无关的推理请求翻译成 Anthropic 的请求体,并把 Anthropic 的响应/流式事件
 * 解析回统一契约。
 *
 * <p><b>MS-C3 JSON_OBJECT 安全桥接</b>:Project Agent 的推理 broker 强制
 * {@link ModelOutputContract.JsonObject} 契约,而 Anthropic Messages 的线上结构化
 * 输出字段是 {@code output_config.format}(官方 GA 字段;旧的 {@code output_format}
 * 已弃用)。本适配层用一个版本化的<b>宿主内部 JSON Envelope</b> 承载该契约:请求侧
 * 要求 Provider 把项目需要的原始 JSON 对象序列化到单一 {@code json} 字符串字段;
 * 响应侧严格验证并解包该 Envelope——只有内层确为语法合法的 JSON 对象时才把规范化
 * 序列化结果作为推理内容返回。外层 Envelope 本身绝不作为决策输出,任何失败
 * (非法完成状态、缺失字段、非对象内层、重复键、非法 Unicode、超长、尾随 token)
 * 一律失败关闭,绝不回退到 Text 或其他模型。
 */
@Component
public class AnthropicMessagesProtocolAdapter implements ProtocolAdapter {

    public static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    /** Envelope 结构的版本化标识;schema 变更时必须同步更新协议文档。 */
    static final String JSON_ENVELOPE_VERSION = "anthropic-json-envelope.v1";
    static final int MAX_ENVELOPE_TEXT_CHARS = 262144;
    static final int MAX_INNER_JSON_CHARS = 131072;

    private static final String ENVELOPE_INSTRUCTION =
            "Serialize the JSON object required by the task as the value of the single \"json\" "
                    + "string field of the output format schema. The \"json\" value must be the "
                    + "complete JSON object serialized as a string.";

    /** 宿主内部 Envelope 的固定 JSON Schema;符合官方 structured-outputs schema 限制。 */
    private static final Map<String, Object> JSON_ENVELOPE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("json", Map.of("type", "string")),
            "required", List.of("json"),
            "additionalProperties", false);

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public CustomApiFormat format() {
        return CustomApiFormat.ANTHROPIC_MESSAGES;
    }

    @Override
    public Map<String, Object> buildRequestBody(ModelInferenceRequest request, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        int maxTokens = request.maxOutputTokens() != null && request.maxOutputTokens() > 0
                ? Math.min(request.maxOutputTokens(), 4096) : DEFAULT_MAX_TOKENS;
        body.put("max_tokens", maxTokens);
        List<Map<String, String>> messages = new ArrayList<>();
        String system = null;
        for (var m : request.messages()) {
            String role = m.role() == null ? "user" : m.role().trim().toLowerCase();
            String content = m.content() == null ? "" : m.content();
            if ("system".equals(role) && system == null) {
                system = content;
                continue;
            }
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", "assistant".equals(role) ? "assistant" : "user");
            mm.put("content", content);
            messages.add(mm);
        }
        ModelOutputContract contract = request.outputContract();
        boolean jsonEnvelope = contract instanceof ModelOutputContract.JsonObject;
        if (jsonEnvelope && system != null && !system.isBlank()) {
            system = system + "\n\n" + ENVELOPE_INSTRUCTION;
        } else if (jsonEnvelope) {
            system = ENVELOPE_INSTRUCTION;
        }
        if (system != null && !system.isBlank()) {
            body.put("system", system);
        }
        if (messages.isEmpty()) {
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", "user");
            mm.put("content", "");
            messages.add(mm);
        }
        body.put("messages", messages);
        body.put("stream", false);
        if (jsonEnvelope) {
            // MS-C3:官方 GA 结构化输出字段是 output_config.format(json_schema)。
            // 这里发送的是宿主内部 Envelope schema,不是 Project 决策 schema 本身。
            Map<String, Object> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_schema");
            fmt.put("schema", JSON_ENVELOPE_SCHEMA);
            Map<String, Object> oc = new LinkedHashMap<>();
            oc.put("format", fmt);
            body.put("output_config", oc);
        } else if (contract instanceof ModelOutputContract.JsonSchema js) {
            Map<String, Object> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_schema");
            fmt.put("schema", js.schema());
            Map<String, Object> oc = new LinkedHashMap<>();
            oc.put("format", fmt);
            body.put("output_config", oc);
        } else if (!(contract instanceof ModelOutputContract.Text)) {
            throw ModelProviderException.invalidResponse("anthropic", "Unsupported output contract");
        }
        return body;
    }

    @Override
    public Map<String, Object> buildStreamRequestBody(ModelInferenceRequest request, String model) {
        if (request.outputContract() instanceof ModelOutputContract.JsonObject) {
            // 流式聚合路径绕过 parseNonStreamResponse,无法保证 Envelope 解包语义;
            // JSON_OBJECT 契约在 Anthropic 上只支持非流式,此处失败关闭而不是把
            // 未验证的 Envelope 文本当决策内容返回。
            throw ModelProviderException.providerRequestError("anthropic",
                    "Anthropic JSON_OBJECT bridge is non-streaming only", null);
        }
        Map<String, Object> body = new LinkedHashMap<>(buildRequestBody(request, model));
        body.put("stream", true);
        return body;
    }

    @Override
    public Map<String, String> authHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-api-key", apiKey.trim());
        }
        return headers;
    }

    @Override
    public ModelInferenceResponse parseNonStreamResponse(JsonNode root, String context) {
        ModelInferenceResponse response = parseTextResponse(root, context);
        if (!isCompletedStopReason(response.finishReason())) {
            throw ModelProviderException.invalidResponse(context,
                    "Anthropic response ended with unsupported stop_reason: " + response.finishReason());
        }
        return response;
    }

    @Override
    public ModelInferenceResponse parseNonStreamResponse(JsonNode root, String context,
            ModelOutputContract contract) {
        if (!(contract instanceof ModelOutputContract.JsonObject)) {
            return parseNonStreamResponse(root, context);
        }
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic response must be an object");
        }
        String stop = root.has("stop_reason") && root.get("stop_reason").isTextual()
                ? root.get("stop_reason").asText() : null;
        if (!isCompletedStopReason(stop)) {
            throw ModelProviderException.invalidResponse(context,
                    "Anthropic JSON bridge requires a completed stop_reason, got: " + stop);
        }
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic response has no content blocks");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block == null || !block.isObject()) {
                throw ModelProviderException.invalidResponse(context, "Unsupported Anthropic content block");
            }
            String type = block.has("type") && block.get("type").isTextual() ? block.get("type").asText() : "";
            switch (type) {
                case "text" -> {
                    if (!block.has("text") || !block.get("text").isTextual()) {
                        throw ModelProviderException.invalidResponse(context, "Malformed text block");
                    }
                    sb.append(block.get("text").asText());
                }
                case "thinking", "redacted_thinking" -> {
                    // 私有推理块绝不进入 Envelope 文本,也不对外呈现。
                }
                default -> throw ModelProviderException.invalidResponse(context,
                        "Anthropic JSON bridge forbids non-text content blocks: " + type);
            }
        }
        if (sb.length() == 0) {
            throw ModelProviderException.invalidResponse(context, "Anthropic JSON bridge got no text block");
        }
        if (sb.length() > MAX_ENVELOPE_TEXT_CHARS) {
            throw ModelProviderException.invalidResponse(context, "Anthropic JSON envelope exceeds size bound");
        }
        String unwrapped = unwrapJsonEnvelope(sb.toString(), context);
        Integer pt = usageToken(root, "input_tokens");
        Integer ct = usageToken(root, "output_tokens");
        return new ModelInferenceResponse(unwrapped, stop, pt, ct);
    }

    /**
     * 严格解包宿主 JSON Envelope:外层必须恰为单一 {@code json} 字符串字段,内层必须
     * 是语法合法的 JSON 对象。返回内层对象的规范化序列化,失败一律抛出协议失败。
     */
    static String unwrapJsonEnvelope(String raw, String context) {
        JsonNode outer;
        try {
            outer = STRICT_MAPPER.readTree(raw);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context,
                    "Anthropic JSON envelope is not valid JSON");
        }
        if (outer == null || !outer.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic JSON envelope must be an object");
        }
        if (outer.size() != 1 || !outer.has("json") || !outer.get("json").isTextual()) {
            throw ModelProviderException.invalidResponse(context,
                    "Anthropic JSON envelope must carry exactly one string field \"json\"");
        }
        String inner = outer.get("json").textValue();
        if (inner.isEmpty()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic JSON envelope value is empty");
        }
        if (inner.length() > MAX_INNER_JSON_CHARS) {
            throw ModelProviderException.invalidResponse(context, "Anthropic inner JSON exceeds size bound");
        }
        if (hasUnpairedSurrogate(inner)) {
            throw ModelProviderException.invalidResponse(context, "Anthropic inner JSON has invalid Unicode");
        }
        JsonNode innerNode;
        try {
            innerNode = STRICT_MAPPER.readTree(inner);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context, "Anthropic inner JSON is not valid JSON");
        }
        if (innerNode == null || !innerNode.isObject()) {
            throw ModelProviderException.invalidResponse(context,
                    "Anthropic inner JSON must be an object, not array/string/number/null");
        }
        try {
            return STRICT_MAPPER.writeValueAsString(innerNode);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context, "Anthropic inner JSON cannot be normalized");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompletedStopReason(String stop) {
        // refusal/max_tokens/pause_turn/tool_use/null 一律不是 JSON 桥接或文本
        // 推理可接受的成功终态。
        return "end_turn".equals(stop) || "stop_sequence".equals(stop);
    }

    private static Integer usageToken(JsonNode root, String field) {
        JsonNode usage = root.get("usage");
        if (usage == null || !usage.isObject() || !usage.has(field) || !usage.get(field).isNumber()) {
            return null;
        }
        return usage.get(field).asInt();
    }

    private ModelInferenceResponse parseTextResponse(JsonNode root, String context) {
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic response must be an object");
        }
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            throw ModelProviderException.invalidResponse(context, "Anthropic response has no content blocks");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block != null && block.isObject()
                    && "text".equals(block.has("type") && block.get("type").isTextual() ? block.get("type").asText() : "")
                    && block.has("text") && block.get("text").isTextual()) {
                sb.append(block.get("text").asText());
            }
        }
        // thinking / tool_use / redacted 类型的内容块永远不会对外呈现。
        if (sb.length() == 0) {
            throw ModelProviderException.invalidResponse(context, "Anthropic response has no text block");
        }
        JsonNode usage = root.get("usage");
        Integer pt = null;
        Integer ct = null;
        if (usage != null && usage.isObject()) {
            if (usage.has("input_tokens") && usage.get("input_tokens").isNumber()) {
                pt = usage.get("input_tokens").asInt();
            }
            if (usage.has("output_tokens") && usage.get("output_tokens").isNumber()) {
                ct = usage.get("output_tokens").asInt();
            }
        }
        String stop = root.has("stop_reason") && root.get("stop_reason").isTextual()
                ? root.get("stop_reason").asText() : null;
        return new ModelInferenceResponse(sb.toString(), stop, pt, ct);
    }

    @Override
    public String extractVisibleText(JsonNode data, String context) {
        if (data == null || !data.isObject()) {
            return null;
        }
        String type = data.has("type") && data.get("type").isTextual() ? data.get("type").asText() : "";
        if ("error".equals(type)) {
            throw ModelProviderException.providerRequestError(context, "Anthropic provider error event", null);
        }
        // 只有带文本增量的 content_block_delta 才进入展示层。
        if ("content_block_delta".equals(type)) {
            JsonNode delta = data.get("delta");
            if (delta != null && delta.isObject()) {
                String dType = delta.has("type") && delta.get("type").isTextual() ? delta.get("type").asText() : "";
                if ("text_delta".equals(dType) && delta.has("text") && delta.get("text").isTextual()) {
                    String t = delta.get("text").asText();
                    return t.isEmpty() ? null : t;
                }
            }
            return null;
        }
        // message_start/content_block_start/content_block_stop/message_delta/
        // message_stop/ping 一律不对外呈现;thinking/tool/input_json 的增量也忽略。
        return null;
    }

    @Override
    public boolean isTerminalData(String rawData, JsonNode data, String context) {
        if (data != null && data.isObject() && data.has("type") && data.get("type").isTextual()) {
            String type = data.get("type").asText();
            if ("message_stop".equals(type) || "error".equals(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSuccessfulTerminal(String rawData, JsonNode data, String context) {
        return data != null && data.isObject() && data.has("type") && data.get("type").isTextual()
                && "message_stop".equals(data.get("type").asText());
    }

    @Override
    public boolean isFailureTerminal(JsonNode data, String context) {
        return data != null && data.isObject() && data.has("type") && data.get("type").isTextual()
                && "error".equals(data.get("type").asText());
    }

    @Override
    public List<String> parseModelList(JsonNode root, String context) {
        return ModelListShapes.parseDataIdList(root, context);
    }

    @Override
    public ModelProviderException mapHttpError(int httpStatus, String bodySnippet, String context) {
        return HttpErrorShapes.map(httpStatus, bodySnippet, context);
    }
}
