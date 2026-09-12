package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenRouter model qualification: free identity, then conservative
 * metadata capability filtering, then the real compatibility probe.
 *
 * <p>No hardcoded model names. Metadata explicitly incompatible, missing,
 * or insufficient for the GA production JSON_OBJECT contract excludes the
 * candidate. The documented exception is {@code openrouter/free}: the
 * official router selects a capable free model per request, so it skips
 * the metadata layer but still faces the real probe.
 */
public final class OpenRouterModelQualification {

    private OpenRouterModelQualification() {
    }

    /** Free-only identity: exact router id or the {@code :free} suffix. */
    public static boolean isFreeModelId(String id) {
        return OpenRouterGatewaySupport.isFreeModelId(id);
    }

    /** Full qualification of one raw {@code GET /models} data entry. */
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

    /** The model must be able to produce text. */
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

    /** The GA production contract needs native JSON structured output. */
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

    /** Qualified ids from a raw model list payload, sorted and bounded. */
    public static List<String> qualifiedIds(JsonNode root, String context) {
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list must be an object");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw ModelProviderException.invalidResponse(context, "OpenRouter model list has no data array");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode item : data) {
            if (isQualified(item)) {
                String id = item.get("id").asText().trim();
                if (id.length() <= ProviderUrlSecurity.MAX_MODEL_ID_LENGTH && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        ids.sort(String::compareTo);
        return ids.size() > 500 ? ids.subList(0, 500) : ids;
    }
}
