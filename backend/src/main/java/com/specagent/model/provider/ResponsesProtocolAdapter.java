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
 * 文件名:ResponsesProtocolAdapter.java
 *
 * 用途:OpenAI Responses 协议的线上格式适配器({@code POST <base>/responses})。
 * 绝不原样发送 Chat Completions 的 {@code response_format} 字段。
 */
@Component
public class ResponsesProtocolAdapter implements ProtocolAdapter {

    @Override
    public CustomApiFormat format() {
        return CustomApiFormat.RESPONSES;
    }

    @Override
    public Map<String, Object> buildRequestBody(ModelInferenceRequest request, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> input = new ArrayList<>();
        request.messages().forEach(m -> {
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", m.role());
            mm.put("content", m.content() == null ? "" : m.content());
            input.add(mm);
        });
        body.put("input", input);
        ModelOutputContract contract = request.outputContract();
        if (contract instanceof ModelOutputContract.JsonObject) {
            Map<String, Object> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_object");
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("format", fmt);
            body.put("text", text);
        } else if (contract instanceof ModelOutputContract.JsonSchema js) {
            Map<String, Object> fmt = new LinkedHashMap<>();
            fmt.put("type", "json_schema");
            fmt.put("name", js.name());
            fmt.put("strict", true);
            fmt.put("schema", js.schema());
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("format", fmt);
            body.put("text", text);
        } else if (!(contract instanceof ModelOutputContract.Text)) {
            throw ModelProviderException.invalidResponse("responses", "Unsupported output contract");
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
            throw ModelProviderException.invalidResponse(context, "Responses response must be an object");
        }
        // V1 fail-closed:当顶层 status/error 表明失败时,绝不接受输出文本。
        // 兼容性探测由此自然拒绝不完整的 Responses 兼容端点。
        if (root.has("error") && root.get("error") != null && !root.get("error").isNull()) {
            JsonNode err = root.get("error");
            String msg = "Responses provider error";
            if (err.isTextual() && !err.asText().isBlank()) {
                msg = err.asText();
            } else if (err.isObject() && err.has("message") && err.get("message").isTextual()) {
                msg = err.get("message").asText();
            }
            throw ModelProviderException.providerRequestError(context, "Responses error: " + safeSnippet(msg), null);
        }
        if (root.has("incomplete_details") && root.get("incomplete_details") != null
                && !root.get("incomplete_details").isNull()) {
            throw ModelProviderException.invalidResponse(context, "Responses response incomplete");
        }
        JsonNode statusNode = root.get("status");
        String status = statusNode != null && statusNode.isTextual() ? statusNode.asText() : null;
        if (status == null || status.isBlank()) {
            throw ModelProviderException.invalidResponse(context, "Responses response has no status");
        }
        if (!"completed".equals(status)) {
            throw ModelProviderException.invalidResponse(context, "Responses finished with status: " + status);
        }
        String text = extractOutputText(root);
        if (text == null) {
            throw ModelProviderException.invalidResponse(context, "Responses response has no output text");
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
        return new ModelInferenceResponse(text, null, pt, ct);
    }

    private static String extractOutputText(JsonNode root) {
        if (root.has("output_text") && root.get("output_text").isTextual()) {
            return root.get("output_text").asText();
        }
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : output) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode c : content) {
                        if (c != null && c.isObject()
                                && "output_text".equals(c.has("type") && c.get("type").isTextual() ? c.get("type").asText() : "")
                                && c.has("text") && c.get("text").isTextual()) {
                            sb.append(c.get("text").asText());
                        }
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return null;
    }

    @Override
    public String extractVisibleText(JsonNode data, String context) {
        if (data == null || !data.isObject()) {
            return null;
        }
        String type = data.has("type") && data.get("type").isTextual() ? data.get("type").asText() : "";
        // 协议错误事件一律作为失败抛出,绝不当作文本。
        if ("response.failed".equals(type) || "error".equals(type)) {
            throw ModelProviderException.providerRequestError(context, "Responses provider error event", null);
        }
        // 按事件类型白名单:只放行 output-text 增量。reasoning、tool、usage、
        // 生命周期和元数据事件一律不对外呈现。
        if ("response.output_text.delta".equals(type)) {
            JsonNode delta = data.get("delta");
            if (delta != null && delta.isTextual()) {
                String t = delta.asText();
                return t.isEmpty() ? null : t;
            }
            return null;
        }
        return null;
    }

    @Override
    public boolean isTerminalData(String rawData, JsonNode data, String context) {
        if (data != null && data.isObject() && data.has("type") && data.get("type").isTextual()) {
            String type = data.get("type").asText();
            if ("response.completed".equals(type) || "response.failed".equals(type)
                    || "response.incomplete".equals(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSuccessfulTerminal(String rawData, JsonNode data, String context) {
        return data != null && data.isObject() && data.has("type") && data.get("type").isTextual()
                && "response.completed".equals(data.get("type").asText());
    }

    @Override
    public boolean isFailureTerminal(JsonNode data, String context) {
        if (data == null || !data.isObject() || !data.has("type") || !data.get("type").isTextual()) {
            return false;
        }
        String type = data.get("type").asText();
        // response.incomplete 是失败终止:部分输出绝不能当作成功的推理结果返回。
        return "response.failed".equals(type) || "response.incomplete".equals(type)
                || "error".equals(type);
    }

    @Override
    public List<String> parseModelList(JsonNode root, String context) {
        return ModelListShapes.parseDataIdList(root, context);
    }

    @Override
    public ModelProviderException mapHttpError(int httpStatus, String bodySnippet, String context) {
        return HttpErrorShapes.map(httpStatus, bodySnippet, context);
    }

    private static String safeSnippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
