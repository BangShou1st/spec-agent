package com.specagent.globalassistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Deterministic tool argument canonicalization for idempotency keys,
 * repeat detection and trace comparison. Map key order never changes semantics.
 */
@Component
public class GlobalAssistantToolArgumentCanonicalizer {
    private final ObjectMapper mapper;
    public GlobalAssistantToolArgumentCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public String canonicalize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(new TreeMap<>(arguments));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize tool arguments", ex);
        }
    }
}
