package com.specagent.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Normalized resource read result with provenance. Phase-one serves text
 * resources only; binary content is rejected with a typed failure.
 */
public record McpResourceContent(
        String uri,
        String text,
        String mimeType,
        Map<String, Object> provenance) {

    public McpResourceContent {
        text = text == null ? "" : text;
        mimeType = mimeType == null ? "text/plain" : mimeType;
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public int byteSize() {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}