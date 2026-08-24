package com.specagent.agent;

import com.specagent.common.Hashes;

import java.util.UUID;

/**
 * Stable logical identity for a client-driven agent-run create request.
 *
 * <p>The fingerprint contains only client-stable request fields. Resolved
 * runtime state such as the current active route or current route tip is
 * deliberately excluded: an idempotent retry must still replay the original
 * run after that run has already advanced or switched graph state.
 *
 * <p>The field order is fixed and each value is length-delimited, so null,
 * empty, whitespace, UUID and text values cannot become ambiguous. The hash
 * intentionally excludes runtime-generated values such as run ids and
 * timestamps.
 */
public final class AgentRunRequestFingerprint {

    private AgentRunRequestFingerprint() {
    }

    public static String forClientRequest(UUID projectId,
                                          String operation,
                                          UUID nodeId,
                                          UUID sourceRouteId,
                                          UUID answerId,
                                          UUID selectedOptionId,
                                          String freeText) {
        String canonical = String.join("|",
                field("projectId", projectId),
                field("operation", operation),
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
