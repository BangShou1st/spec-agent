package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:OpenRouterModelQualification.java
 *
 * 用途:OpenRouter 模型资格判定:先看免费身份,再做保守的元数据能力过滤,
 * 最后由真实的兼容性探测把关。
 *
 * 不硬编码任何模型名。元数据明确不兼容、缺失,或不足以支撑 GA 生产所需的
 * JSON_OBJECT 契约的候选模型会被排除。文档化的例外是 {@code openrouter/free}:
 * 官方路由会为每次请求挑选一个有能力的免费模型,因此它跳过元数据层,但仍要
 * 通过真实探测。
 */
public final class OpenRouterModelQualification {

    private OpenRouterModelQualification() {
    }

    /** 免费身份判定:精确的路由 id,或带 {@code :free} 后缀。 */
    public static boolean isFreeModelId(String id) {
        return OpenRouterGatewaySupport.isFreeModelId(id);
    }

    /** 对一条原始 {@code GET /models} 数据条目做完整资格判定。 */
    public static boolean isQualified(JsonNode entry) {
        if (entry == null || !entry.isObject()) {
            return false;
        }
        JsonNode idNode = entry.get("id");
        if (idNode == null || !idNode.isTextual()) {
            return false;
        }
        String id = idNode.asText().trim();
        if (!isFreeModelId(id)) {
            return false;
        }
        if ("openrouter/free".equals(id)) {
            return true;
        }
        return hasTextOutput(entry) && hasStructuredOutput(entry);
    }

    /** 模型必须能够产出文本。 */
    static boolean hasTextOutput(JsonNode entry) {
        JsonNode arch = entry.get("architecture");
        if (arch == null || !arch.isObject()) {
            return false;
        }
        JsonNode out = arch.get("output_modalities");
        if (out == null || !out.isArray()) {
            return false;
        }
        for (JsonNode m : out) {
            if (m.isTextual() && "text".equals(m.asText())) {
                return true;
            }
        }
        return false;
    }

    /** GA 生产契约需要原生 JSON 结构化输出能力。 */
    static boolean hasStructuredOutput(JsonNode entry) {
        JsonNode params = entry.get("supported_parameters");
        if (params == null || !params.isArray()) {
            return false;
        }
        for (JsonNode p : params) {
            if (p.isTextual() && ("response_format".equals(p.asText())
                    || "structured_outputs".equals(p.asText()))) {
                return true;
            }
        }
        return false;
    }

    /** 从原始模型列表载荷中提取合格 id,排序并限量。 */
    public static List<String> qualifiedIds(JsonNode root, String context) {
        return qualifiedModelIds(root, context).qualified();
    }

    /** 完整资格结果:可展示的全部 id 及其中的免费合格子集。 */
    public record QualifiedModelIds(List<String> all, List<String> qualified) {
    }

    /**
     * 从一条原始 {@code GET /models} 载荷中提取全部可展示的模型 id(免费 + 付费)
     * 以及免费合格子集。付费条目跳过元数据能力过滤:展示它们只是显示层面的事,
     * 保存/校验时仍由真实探测把关。
     */
    public static QualifiedModelIds qualifiedModelIds(JsonNode root, String context) {
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list must be an object");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list has no data array");
        }
        List<String> all = new ArrayList<>();
        List<String> qualified = new ArrayList<>();
        for (JsonNode item : data) {
            if (item == null || !item.isObject()) {
                continue;
            }
            JsonNode idNode = item.get("id");
            if (idNode == null || !idNode.isTextual()) {
                continue;
            }
            String id = idNode.asText().trim();
            if (id.isEmpty() || id.length() > ProviderUrlSecurity.MAX_MODEL_ID_LENGTH || all.contains(id)) {
                continue;
            }
            all.add(id);
            if (isQualified(item)) {
                qualified.add(id);
            }
        }
        all.sort(String::compareTo);
        qualified.sort(String::compareTo);
        return new QualifiedModelIds(bound(all), bound(qualified));
    }

    private static List<String> bound(List<String> ids) {
        return ids.size() > 500 ? ids.subList(0, 500) : ids;
    }
}
