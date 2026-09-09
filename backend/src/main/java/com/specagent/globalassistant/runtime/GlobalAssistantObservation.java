package com.specagent.globalassistant.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed, bounded, sanitized tool observation for the next model decision.
 * Never exception stacks, SQL, credentials, provider JSON, or CoT.
 */
public record GlobalAssistantObservation(String capabilityId, boolean ok, Map<String, Object> data, String errorCode) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capabilityId", capabilityId);
        map.put("ok", ok);
        if (data != null) {
            data.forEach((k, v) -> {
                String text = String.valueOf(v);
                map.put(k, text.length() <= 2000 ? v : text.substring(0, 2000) + "\u2026");
            });
        }
        if (errorCode != null) {
            map.put("errorCode", errorCode);
        }
        return map;
    }
}
