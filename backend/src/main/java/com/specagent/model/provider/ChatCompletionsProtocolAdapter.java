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
 * OpenAI Chat Completions wire translation ({@code POST <base>/chat/completions}).
 */
@Component
public class ChatCompletionsProtocolAdapter implements ProtocolAdapter {

    @Override
    public CustomApiFormat format() {
        return CustomApiFormat.CHAT_COMPLETIONS;
    }

    @Override
    public Map<String, Object> buildRequestBody(ModelInferenceRequest request, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        request.messages().forEach(m -> {
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", m.role());
            mm.put("content", m.content() == null ? "" : m.content());
            messages.add(mm);
        });
        body.put("messages", messages);
        ModelOutputContract contract = request.outputContract();
        if (contract instanceof ModelOutputContract.JsonObject) {
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("type", "json_object");
            body.put("response_format", rf);
        } else if (contract instanceof ModelOutputContract.JsonSchema js) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("name", js.name());
            wrapper.put("strict", true);
            wrapper.put("schema", js.schema());
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("type", "json_schema");
            rf.put("json_schema", wrapper);
            body.put("response_format", rf);
        } else if (!(contract instanceof ModelOutputContract.Text)) {
            throw ModelProviderException.invalidResponse("chat-completions", "Unsupported output contract");
        }
        return body;
    }

    @Override
    public Map<String, String> authHeaders(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + apiKey.trim());
    }

    @Override
    public ModelInferenceResponse parseNonStreamResponse(JsonNode root, String context) {
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Chat completions response must be an object");
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw ModelProviderException.invalidResponse(context, "Chat completions response has no choices");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Chat completions choice has no message");
        }
        JsonNode content = message.get("content");
        String text = content != null && content.isTextual() ? content.asText() : null;
        if (text == null) {
            throw ModelProviderException.invalidResponse(context, "Chat completions message has no text content");
        }
        String finish = extractFinishReason(choices.get(0));
        // V1 fail-closed terminal semantics: only stop is a successful
        // non-stream terminal. length is truncation, content_filter is not a
        // complete authoritative response, and provider-native tool/function
        // calling is disabled in Spec Agent. Missing/null has no [DONE] to
        // prove completion, so it also fails closed.
        if (finish == null || finish.isBlank() || "null".equals(finish)) {
            throw ModelProviderException.invalidResponse(context, "Chat completions response has no finish_reason");
        }
        if (!"stop".equals(finish)) {
            throw ModelProviderException.invalidResponse(context, "Chat completions finished with reason: " + finish);
        }
        Integer pt = null;
        Integer ct = null;
        JsonNode usage = root.get("usage");
        if (usage != null && usage.isObject()) {
            if (usage.has("prompt_tokens") && usage.get("prompt_tokens").isNumber()) {
                pt = usage.get("prompt_tokens").asInt();
            }
            if (usage.has("completion_tokens") && usage.get("completion_tokens").isNumber()) {
                ct = usage.get("completion_tokens").asInt();
            }
        }
        return new ModelInferenceResponse(text, finish, pt, ct);
    }

    @Override
    public String extractVisibleText(JsonNode data, String context) {
        if (data == null || !data.isObject()) {
            return null;
        }
        if (data.has("error")) {
            throw mapProtocolErrorNode(data.get("error"), context);
        }
        JsonNode choices = data.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null || !delta.isObject()) {
            return null;
        }
        // Whitelist ONLY choices[].delta.content. reasoning_content, reasoning,
        // tool_calls, function_call, usage and provider metadata never surface.
        JsonNode content = delta.get("content");
        if (content != null && content.isTextual()) {
            String t = content.asText();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    @Override
    public boolean isTerminalData(String rawData, JsonNode data, String context) {
        if (rawData != null && rawData.trim().equals("[DONE]")) {
            return true;
        }
        String finish = extractFinishReason(data);
        return finish != null && !finish.isBlank() && !"null".equals(finish);
    }

    @Override
    public boolean isSuccessfulTerminal(String rawData, JsonNode data, String context) {
        // V1 fail-closed: only stop and [DONE] are successful terminals.
        // length (truncation), content_filter (not authoritative), and
        // provider-native tool_calls/function_call (disabled) are failures.
        // [DONE] without an explicit stop stays success when no failure
        // finish reason preceded it (failures throw immediately in postSse).
        if (rawData != null && rawData.trim().equals("[DONE]")) {
            return true;
        }
        return "stop".equals(extractFinishReason(data));
    }

    @Override
    public boolean isFailureTerminal(JsonNode data, String context) {
        if (data != null && data.isObject() && data.has("error")) {
            return true;
        }
        String finish = extractFinishReason(data);
        if (finish == null || finish.isBlank() || "null".equals(finish)) {
            return false;
        }
        // Any non-stop finish_reason is a failure terminal (length,
        // content_filter, tool_calls, function_call, and future values).
        return !"stop".equals(finish);
    }

    private static String extractFinishReason(JsonNode data) {
        if (data == null || !data.isObject()) {
            return null;
        }
        JsonNode choices = data.has("choices") ? data.get("choices") : null;
        // Non-stream passes choices.get(0) directly.
        JsonNode holder = data;
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            holder = choices.get(0);
        } else if (!data.has("finish_reason")) {
            return null;
        }
        JsonNode finish = holder.get("finish_reason");
        if (finish != null && finish.isTextual()) {
            return finish.asText();
        }
        return null;
    }

    @Override
    public List<String> parseModelList(JsonNode root, String context) {
        return ModelListShapes.parseDataIdList(root, context);
    }

    @Override
    public ModelProviderException mapHttpError(int httpStatus, String bodySnippet, String context) {
        return HttpErrorShapes.map(httpStatus, bodySnippet, context);
    }

    private ModelProviderException mapProtocolErrorNode(JsonNode error, String context) {
        String code = error.has("code") && error.get("code").isTextual() ? error.get("code").asText() : "";
        String msg = error.has("message") && error.get("message").isTextual() ? error.get("message").asText() : "provider error";
        String lc = (code + " " + msg).toLowerCase();
        if (lc.contains("unauthorized") || lc.contains("invalid api key") || lc.contains("authentication")) {
            return ModelProviderException.authentication(context, "Provider rejected the credential", null);
        }
        if (lc.contains("rate")) {
            return ModelProviderException.rateLimited(context, "Provider rate limited the request");
        }
        if (lc.contains("model") && (lc.contains("not found") || lc.contains("does not exist"))) {
            return ModelProviderException.invalidModel(context, "Model does not exist", null);
        }
        return ModelProviderException.providerRequestError(context, "Provider error: " + safeSnippet(msg), null);
    }

    private static String safeSnippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
