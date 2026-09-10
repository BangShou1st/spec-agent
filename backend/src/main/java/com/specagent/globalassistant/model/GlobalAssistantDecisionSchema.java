package com.specagent.globalassistant.model;

import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the Global Assistant decision JSON shape V2.
 *
 * <p>Discriminated executable state machine: top-level {@code kind} selects
 * exactly one of TOOL / CLARIFY / NAVIGATE / FINAL. Each branch forbids
 * additional properties so the schema describes legal decisions, not just
 * field types. Serves the neutral {@link ModelOutputContract} schema and
 * tests from one definition so the wire shape cannot drift from the strict
 * parser. The parser ({@link GlobalAssistantDecisionParser}) and the
 * validator ({@link GlobalAssistantDecisionValidator}) remain the executable
 * authority: invariants that cannot be expressed as static JSON Schema (for
 * example project existence) stay validator-only and are documented in the
 * parity test.
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

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static Map<String, Object> build() {
        Map<String, Object> createArgs = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "minLength", 1, "maxLength", 200)),
                "required", List.of("title"),
                "additionalProperties", false);
        Map<String, Object> searchArgs = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                "required", List.of("query"),
                "additionalProperties", false);
        Map<String, Object> recentArgs = Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                "required", List.of(),
                "additionalProperties", false);
        Map<String, Object> summaryArgs = Map.of(
                "type", "object",
                "properties", Map.of(
                        "projectId", Map.of("type", "string", "pattern", UUID_PATTERN)),
                "required", List.of("projectId"),
                "additionalProperties", false);
        Map<String, Object> createBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "capabilityId", Map.of("const", "project.create"),
                        "arguments", createArgs),
                "required", List.of("capabilityId", "arguments"),
                "additionalProperties", false);
        Map<String, Object> searchBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "capabilityId", Map.of("const", "project.search"),
                        "arguments", searchArgs),
                "required", List.of("capabilityId", "arguments"),
                "additionalProperties", false);
        Map<String, Object> recentBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "capabilityId", Map.of("const", "project.list_recent"),
                        "arguments", recentArgs),
                "required", List.of("capabilityId", "arguments"),
                "additionalProperties", false);
        Map<String, Object> summaryBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "capabilityId", Map.of("const", "project.get_summary"),
                        "arguments", summaryArgs),
                "required", List.of("capabilityId", "arguments"),
                "additionalProperties", false);
        Map<String, Object> toolBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("const", "TOOL"),
                        "toolRequest", Map.of("oneOf", List.of(createBranch, searchBranch, recentBranch, summaryBranch))),
                "required", List.of("kind", "toolRequest"),
                "additionalProperties", false);
        Map<String, Object> clarifyBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("const", "CLARIFY"),
                        "assistantText", Map.of("type", "string", "minLength", 1, "maxLength", 4000)),
                "required", List.of("kind", "assistantText"),
                "additionalProperties", false);
        Map<String, Object> projectNav = Map.of(
                "type", "object",
                "properties", Map.of(
                        "destination", Map.of("const", "PROJECT"),
                        "resourceId", Map.of("type", "string", "pattern", UUID_PATTERN)),
                "required", List.of("destination", "resourceId"),
                "additionalProperties", false);
        Map<String, Object> nonProjectNav = Map.of(
                "type", "object",
                "properties", Map.of(
                        "destination", Map.of(
                                "type", "string",
                                "enum", List.of("PROJECTS", "SKILLS", "CONNECTIONS", "SETTINGS"))),
                "required", List.of("destination"),
                "additionalProperties", false);
        Map<String, Object> navigateBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("const", "NAVIGATE"),
                        "assistantText", Map.of("type", List.of("string", "null"), "maxLength", 4000),
                        "uiAction", Map.of("oneOf", List.of(projectNav, nonProjectNav))),
                "required", List.of("kind", "uiAction"),
                "additionalProperties", false);
        Map<String, Object> finalBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("const", "FINAL"),
                        "assistantText", Map.of("type", "string", "minLength", 1, "maxLength", 4000)),
                "required", List.of("kind", "assistantText"),
                "additionalProperties", false);
        return Map.of(
                "type", "object",
                "required", List.of("kind"),
                "oneOf", List.of(toolBranch, clarifyBranch, navigateBranch, finalBranch));
    }
}
