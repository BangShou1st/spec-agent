package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.model.inference.ModelOutputContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Anthropic Messages wire translation ({@code POST <base>/messages}).
 */
@Component
public class AnthropicMessagesProtocolAdapter implements ProtocolAdapter {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

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
        // V1 frozen decision: GA production speaks JSON_OBJECT and Anthropic
        // Messages has no valid wire mapping for it. Fail closed HERE,
        // before any HTTP request, instead of sending a guessed field.
        // Text and JsonSchema mappings below are retained as internal
        // groundwork only; they never satisfy the GA production contract.
        ModelOutputContract contract = request.outputContract();
        if (contract instanceof ModelOutputContract.JsonObject) {
            throw ModelProviderException.providerRequestError("anthropic",
                    "Anthropic Messages cannot serve the required JSON_OBJECT contract in V1", null);
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
        // Thinking / tool_use / redacted blocks are never surfaced.
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
        // Only content_block_delta with a text delta enters presentation.
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
        // message_stop/ping never surface; thinking/tool/input_json deltas ignored.
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
