package com.specagent.assistant.model;

import com.specagent.model.contract.ModelOutputContract;
import java.util.List;
import java.util.Map;

/**
 * 文件名:GlobalAssistantDecisionSchema.java
 *
 * 用途:全局助手决策 JSON 结构(V2)的唯一事实来源。
 *
 * 判别式的可执行状态机:顶层 {@code kind} 恰好选择 TOOL / CLARIFY /
 * NAVIGATE / FINAL 之一。每个分支都禁止额外属性,因此这个 schema 描述的是
 * "合法的决策"而不只是字段类型。它同时供中立的 {@link ModelOutputContract}
 * schema 和测试使用,保证线上格式不会与严格解析器漂移。
 * 解析器({@link GlobalAssistantDecisionParser})与校验器
 * ({@link GlobalAssistantDecisionValidator})仍是可执行的权威:
 * 无法用静态 JSON Schema 表达的不变量(例如项目是否存在)只由校验器把关,
 * 并在一致性测试中留有记录。
 */
public final class GlobalAssistantDecisionSchema {

    /** 随 schema 一起下发给供应商的稳定契约名。 */
    public static final String CONTRACT_NAME = "global_assistant_decision";

    private static final Map<String, Object> SCHEMA = build();

    private GlobalAssistantDecisionSchema() {
    }

    /** 不可变的决策 JSON Schema(仅用普通 JSON 类型)。 */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    /** 决策推理用的中立结构化输出契约。 */
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
        Map<String, Object> skillImportArgs = Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                        "ref", Map.of("type", "string", "maxLength", 200),
                        "skill", Map.of("type", "string", "maxLength", 512)),
                "required", List.of("url"),
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
        Map<String, Object> skillImportBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "capabilityId", Map.of("const", "skill.import"),
                        "arguments", skillImportArgs),
                "required", List.of("capabilityId", "arguments"),
                "additionalProperties", false);
        Map<String, Object> toolBranch = Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("const", "TOOL"),
                        "toolRequest", Map.of("oneOf", List.of(createBranch, searchBranch, recentBranch, summaryBranch, skillImportBranch))),
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
