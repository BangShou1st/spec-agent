package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared {@code GET <base>/models} tolerant parser: {@code data[].id}. */
final class ModelListShapes {
    private ModelListShapes() {
    }

    static List<String> parseDataIdList(JsonNode root, String context) {
        if (root == null || !root.isObject()) {
            throw ModelProviderException.invalidResponse(context, "Model list response must be an object");
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw ModelProviderException.invalidResponse(context, "Model list response has no data array");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : data) {
            if (item != null && item.isObject() && item.has("id") && item.get("id").isTextual()) {
                String id = item.get("id").asText().trim();
                if (!id.isEmpty() && id.length() <= ProviderUrlSecurity.MAX_MODEL_ID_LENGTH) {
                    seen.add(id);
                }
            }
        }
        List<String> sorted = new ArrayList<>(seen);
        sorted.sort(String::compareTo);
        if (sorted.size() > 500) {
            return sorted.subList(0, 500);
        }
        return sorted;
    }
}
