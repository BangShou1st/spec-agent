package com.specagent.globalassistant.model;

import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the Global Assistant decision JSON shape.
 *
 * <p>Serves the neutral {@link ModelOutputContract} schema and tests from
 * one definition so the wire shape cannot drift from the strict parser. The
 * parser ({@link GlobalAssistantDecisionParser}) and the validator
 * ({@link GlobalAssistantDecisionValidator}) remain the executable
 * authority: business invariants beyond JSON types (for example a tool
 * decision requiring {@code done=false}) stay in the validator and are
 * deliberately not encoded as schema combinators.
 */
public final class GlobalAssistantDecisionSchema {

    /** Stable contract name sent to providers alongside the schema. */
    public static final String CONTRACT_NAME = "global_assistant_decision";

    private static final Map<String, Object> SCHEMA = build();

    private GlobalAssistantDecisionSchema() {
    }

    /** Immutable decision JSON Schema (plain JSON types only). */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Neutral structured-output contract for decision inference. */
    public static ModelOutputContract.JsonSchema contract() {
        return ModelOutputContract.jsonSchema(CONTRACT_NAME, SCHEMA);
    }

    private static Map<String, Object> build() {
        Map<String, Object> toolRequest = Map.of(
                "type", List.of("object", "null"),
                "properties", Map.of(
                        "capabilityId", Map.of("type", "string"),
                        "arguments", Map.of("type", "object")),
                "required", List.of("capabilityId"),
                "additionalProperties", false);
        Map<String, Object> uiAction = Map.of(
                "type", List.of("object", "null"),
                "properties", Map.of(
                        "destination", Map.of(
                                "type", "string",
                                "enum", List.of("PROJECT", "PROJECTS", "SKILLS",
                                        "CONNECTIONS", "SETTINGS")),
                        "resourceId", Map.of("type", List.of("string", "null"))),
                "required", List.of("destination"),
                "additionalProperties", false);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "assistantText", Map.of("type", List.of("string", "null")),
                        "statusText", Map.of("type", List.of("string", "null")),
                        "toolRequest", toolRequest,
                        "uiAction", uiAction,
                        "requiresUserInput", Map.of("type", "boolean"),
                        "done", Map.of("type", "boolean")),
                "required", List.of("requiresUserInput", "done"),
                "additionalProperties", false);
    }
}
