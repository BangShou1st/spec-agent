package com.specagent.model.inference;

import java.util.Map;

/**
 * Provider-neutral semantic output contract for one model inference call.
 *
 * <p>The caller declares the <em>semantic shape</em> it requires; provider
 * adapters translate that declaration into provider-native wire fields (for
 * example the OpenCode {@code response_format}). Provider wire types must
 * never appear here, and provider-specific settings must never leak into
 * callers. Adapters fail closed on contract variants they do not support
 * instead of silently downgrading to text.
 *
 * <p>V1 carries exactly two variants: {@link Text} (ordinary prose) and
 * {@link JsonSchema} (native structured output). Requests without an
 * explicit contract behave as {@link Text}, preserving historical behavior.
 */
public sealed interface ModelOutputContract
        permits ModelOutputContract.Text, ModelOutputContract.JsonSchema {

    /** Ordinary text output; the provider request carries no format enforcement. */
    record Text() implements ModelOutputContract {
    }

    /**
     * Native structured-output request for one named JSON object schema.
     * The schema map uses plain JSON types only (String, Number, Boolean,
     * Map, List, null) so it stays serializable across the Java/Python
     * broker boundary if a future protocol version carries it.
     */
    record JsonSchema(String name, Map<String, Object> schema) implements ModelOutputContract {

        private static final int MAX_NAME_LENGTH = 64;

        public JsonSchema {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("JSON schema contract name is required");
            }
            String trimmed = name.trim();
            if (trimmed.length() > MAX_NAME_LENGTH || !trimmed.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException(
                        "JSON schema contract name must match [A-Za-z0-9_-]{1,64}");
            }
            name = trimmed;
            if (schema == null || schema.isEmpty()) {
                throw new IllegalArgumentException("JSON schema contract schema is required");
            }
            if (!"object".equals(schema.get("type"))) {
                throw new IllegalArgumentException(
                        "JSON schema contract must describe a top-level JSON object");
            }
            schema = Map.copyOf(schema);
        }
    }

    /** Ordinary text output contract. */
    static Text text() {
        return new Text();
    }

    /** Structured-output contract for one named JSON object schema. */
    static JsonSchema jsonSchema(String name, Map<String, Object> schema) {
        return new JsonSchema(name, schema);
    }
}
