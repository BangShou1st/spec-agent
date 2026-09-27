package com.specagent.assistant.tool;

import java.util.UUID;

/**
 * 文件名:GlobalAssistantToolPresentation.java
 *
 * 用途:全局助手各能力的集中展示元数据登记表。
 *
 * 工具运行中/完成后展示的文案都出自这里,仅按能力 ID 索引——
 * 绝不取自用户提示词、模型正文、项目名或分页面特例。
 * 新增一个能力只需要在这里加一条(外加实现与注册),任何 UI 的
 * switch 语句都不必学习新工具。未知 ID 走通用兜底文案,
 * UI 永远不会因为新工具而崩溃。
 */
public final class GlobalAssistantToolPresentation {

    private GlobalAssistantToolPresentation() {}

    public record Presentation(String runningMessage, String resultKind) {}

    private static final java.util.Map<String, Presentation> ENTRIES = java.util.Map.of(
            "project.create", new Presentation("Creating project", "PROJECT"),
            "project.search", new Presentation("Searching projects", "PROJECT_LIST"),
            "project.list_recent", new Presentation("Listing recent projects", "PROJECT_LIST"),
            "project.get_summary", new Presentation("Reading project summary", "PROJECT"),
            "skill.import", new Presentation("Staging skill import", null),
            "skill.import.discover", new Presentation("Inspecting skill repository", null));

    private static final Presentation FALLBACK = new Presentation("Working", null);

    /** 工具结束后、最终模型阶段开始时展示的通用文案。 */
    public static final String COMPOSING_MESSAGE = "Composing answer";

    public static Presentation forCapability(String capabilityId) {
        if (capabilityId == null) return FALLBACK;
        return ENTRIES.getOrDefault(capabilityId, FALLBACK);
    }

    public static String runningMessage(String capabilityId) {
        return forCapability(capabilityId).runningMessage();
    }

    public static String resultKind(String capabilityId) {
        return forCapability(capabilityId).resultKind();
    }

    /**
     * 各能力的结果形状。{@code listKey} 指向结果里存放项目列表的字段名;
     * 单结果工具为 null,其内容对象本身就是条目。下方共享的 PROJECT
     * 字段名适用于所有 V1 能力,只有列表字段各不相同。
     */
    public record ResultShape(String listKey) {}

    static final String FIELD_ID = "projectId";
    static final String FIELD_TITLE = "title";
    static final String FIELD_UPDATED_AT = "updatedAt";
    static final int MAX_LABEL_CODE_POINTS = 200;
    static final int MAX_REFS = 10;
    static final int MAX_UPDATED_AT_CHARS = 64;

    private static final java.util.Map<String, ResultShape> RESULT_SHAPES = java.util.Map.of(
            "project.create", new ResultShape(null),
            "project.search", new ResultShape("candidates"),
            "project.list_recent", new ResultShape("projects"),
            "project.get_summary", new ResultShape(null));

    /** 能力的结果形状;登记表中没有时返回 null。 */
    public static ResultShape resultShapeOf(String capabilityId) {
        if (capabilityId == null) return null;
        return RESULT_SHAPES.get(capabilityId);
    }

    /**
     * 按码点安全的有界截断:绝不拆开 UTF-16 代理项对。
     * 不需要字素库;孤立的代理项不会被产出。
     */
    static String truncateLabel(String value) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= MAX_LABEL_CODE_POINTS) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_LABEL_CODE_POINTS));
    }

    /**
     * 从能力结果投影出类型化的 PROJECT 资源引用。只有真实 UUID 才入选;
     * 未知能力返回空列表。只读结构化的工具内容,绝不读模型正文。
     */
    public static java.util.List<java.util.Map<String, Object>> projectResources(
            String capabilityId, java.util.Map<String, Object> content) {
        ResultShape shape = resultShapeOf(capabilityId);
        if (shape == null || content == null) return java.util.List.of();
        Object raw = shape.listKey() == null ? content : content.get(shape.listKey());
        java.util.List<?> items;
        if (raw instanceof java.util.List<?> list) {
            items = list;
        } else if (shape.listKey() == null && raw instanceof java.util.Map<?, ?>) {
            items = java.util.List.of(raw);
        } else {
            return java.util.List.of();
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof java.util.Map<?, ?> m)) continue;
            Object id = m.get(FIELD_ID);
            Object title = m.get(FIELD_TITLE);
            if (!(id instanceof String idText) || title == null) continue;
            UUID parsed;
            try {
                parsed = UUID.fromString(idText);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            Object updated = m.get(FIELD_UPDATED_AT);
            if (updated != null) {
                String u = String.valueOf(updated);
                if (!u.isBlank()) {
                    meta.put(FIELD_UPDATED_AT, u.length() <= MAX_UPDATED_AT_CHARS ? u : u.substring(0, MAX_UPDATED_AT_CHARS));
                }
            }
            java.util.Map<String, Object> ref = new java.util.LinkedHashMap<>();
            ref.put("kind", "PROJECT");
            ref.put("id", parsed.toString());
            ref.put("label", truncateLabel(String.valueOf(title)));
            ref.put("metadata", meta);
            out.add(ref);
            if (out.size() >= MAX_REFS) break;
        }
        return out;
    }

    /**
     * 列表形状能力的候选 ID/标题对,写入工作状态。
     * 单结果与未知能力返回空列表,调用方因此保持连续性状态原样不动。
     */
    public static java.util.List<java.util.Map<String, String>> candidatePairs(
            String capabilityId, java.util.Map<String, Object> content) {
        ResultShape shape = resultShapeOf(capabilityId);
        if (shape == null || shape.listKey() == null || content == null) return java.util.List.of();
        Object raw = content.get(shape.listKey());
        if (!(raw instanceof java.util.List<?> list)) return java.util.List.of();
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof java.util.Map<?, ?> m) {
                Object id = m.get(FIELD_ID);
                Object title = m.get(FIELD_TITLE);
                if (id != null && title != null) {
                    out.add(java.util.Map.of(FIELD_ID, String.valueOf(id), FIELD_TITLE, String.valueOf(title)));
                }
            }
            if (out.size() >= MAX_REFS) break;
        }
        return out;
    }

    /**
     * 单结果能力的直接项目 ID;缺失或形状不是单结果时返回 null。
     * 与此前的解析语义保持一致。
     */
    public static String directProjectId(String capabilityId, java.util.Map<String, Object> content) {
        ResultShape shape = resultShapeOf(capabilityId);
        if (shape == null || shape.listKey() != null || content == null) return null;
        Object id = content.get(FIELD_ID);
        return id == null ? null : String.valueOf(id);
    }
}
