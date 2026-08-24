package com.specagent.agent;

import com.specagent.common.Hashes;

import java.util.UUID;

/**
 * Stable logical identity for a client-driven agent-run create request.
 *
 * <p>The field order is fixed and each value is length-delimited, so null,
 * empty, whitespace, UUID and text values cannot become ambiguous. The hash
 * intentionally excludes runtime-generated values such as run ids and
 * timestamps.
 */
public final class AgentRunRequestFingerprint {

    private AgentRunRequestFingerprint() {
    }

    public static String forRequest(UUID projectId,
                                    String operation,
                                    UUID routeId,
                                    UUID nodeId,
                                    UUID sourceRouteId,
                                    UUID answerId,
                                    UUID selectedOptionId,
                                    String freeText) {
        String canonical = String.join("|",
                field("projectId", projectId),
                field("operation", operation),
                field("routeId", routeId),
                field("nodeId", nodeId),
                field("sourceRouteId", sourceRouteId),
                field("answerId", answerId),
                field("selectedOptionId", selectedOptionId),
                field("freeText", freeText));
        return Hashes.sha256Hex(canonical);
    }

    private static String field(String name, Object value) {
        if (value == null) {
            return name + ":null";
        }
        String text = value.toString();
        return name + ":" + text.length() + ":" + text;
    }
}
