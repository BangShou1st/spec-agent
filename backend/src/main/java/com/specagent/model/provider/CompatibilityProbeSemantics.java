package com.specagent.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;

/** The existing provider activation probe only; never an executable Agent decision. */
@Service
public class CompatibilityProbeSemantics implements CompatibilityDecisionSemantics {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public Object parseDecision(String content) throws Exception { return mapper.readTree(content); }
    public void validateDecision(Object value) {
        if (!(value instanceof JsonNode node) || !node.isObject() || node.size() != 2
                || !node.path("kind").isTextual() || !node.path("assistantText").isTextual())
            throw new IllegalArgumentException("Invalid compatibility probe shape");
        String text = node.path("assistantText").textValue();
        if (text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Invalid probe text");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid probe Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid probe Unicode");
        }
    }
    public boolean isFinal(Object value) { return "FINAL".equals(((JsonNode) value).path("kind").textValue()); }
}
