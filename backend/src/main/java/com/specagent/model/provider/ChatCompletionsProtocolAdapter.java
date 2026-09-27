package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 文件名:ChatCompletionsProtocolAdapter.java
 *
 * 用途:OpenAI Chat Completions 协议的线上格式适配器
 * ({@code POST <base>/chat/completions})。把统一推理请求翻译成 OpenAI 风格的
 * 请求体,并把非流式响应与 SSE 流式事件解析回统一契约。
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
        // V1 fail-closed 终止语义:非流式下只有 stop 才算成功的终止。
        // length 表示被截断,content_filter 不算完整可信的响应,而提供商原生的
        // tool/function 调用在 Spec Agent 中已被禁用。缺失或为 null 的
        // finish_reason 没有任何凭据能证明补全已完成,因此同样按失败处理。
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
        // 白名单:只放行 choices[].delta.content。reasoning_content、reasoning、
        // tool_calls、function_call、usage 以及提供商元数据一律不对外呈现。
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
        // V1 fail-closed:只有 stop 和 [DONE] 才算成功终止。
        // length(截断)、content_filter(不可信)以及提供商原生的
        // tool_calls/function_call(已禁用)都按失败处理。
        // 收到 [DONE] 但没有显式 stop 时,只要此前未出现过失败原因仍算成功
        // (失败会在 postSse 中立即抛出)。
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
        // 任何非 stop 的 finish_reason 都按失败终止处理(length、
        // content_filter、tool_calls、function_call 以及未来新增的值)。
        return !"stop".equals(finish);
    }

    private static String extractFinishReason(JsonNode data) {
        if (data == null || !data.isObject()) {
            return null;
        }
        JsonNode choices = data.has("choices") ? data.get("choices") : null;
        // 非流式响应直接传入 choices.get(0)。
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
