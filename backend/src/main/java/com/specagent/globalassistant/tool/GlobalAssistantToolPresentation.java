package com.specagent.globalassistant.tool;

import java.util.UUID;

/**
 * Central presentation metadata for Global Assistant capabilities.
 *
 * <p>Labels shown while a tool runs or after it completes come from here,
 * keyed only by capability id - never from user prompt text, model prose,
 * project names, or per-page special cases. Adding a capability means adding
 * one entry here (plus implementation and registration); no UI switch statements
 * need to learn the new tool. Unknown ids fall back to generic labels so the
 * UI can never crash on a new tool.
 */
public final class GlobalAssistantToolPresentation {

    private GlobalAssistantToolPresentation() {}

    public record Presentation(String runningMessage, String resultKind) {}

    private static final java.util.Map<String, Presentation> ENTRIES = java.util.Map.of(
            "project.create", new Presentation("Creating project", "PROJECT"),
            "project.search", new Presentation("Searching projects", "PROJECT_LIST"),
            "project.list_recent", new Presentation("Listing recent projects", "PROJECT_LIST"),
            "project.get_summary", new Presentation("Reading project summary", "PROJECT"));

    private static final Presentation FALLBACK = new Presentation("Working", null);

    /** Generic message shown when the final model phase starts after a tool. */
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
     * Per-capability result shape. {@code listKey} names the result field
     * holding project items, or is null for single-result tools whose
     * content object itself is the item. The shared PROJECT field names
     * below apply to every V1 capability; only the list field varies.
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

    /** Result shape for a capability, or null when the registry knows none. */
    public static ResultShape resultShapeOf(String capabilityId) {
        if (capabilityId == null) return null;
        return RESULT_SHAPES.get(capabilityId);
    }

    /**
     * Code-point-safe bounded truncation: never splits a UTF-16 surrogate
     * pair. No grapheme library needed; lone surrogates cannot be emitted.
     */
    static String truncateLabel(String value) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) <= MAX_LABEL_CODE_POINTS) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_LABEL_CODE_POINTS));
    }

    /**
     * Typed PROJECT resource refs projected from a capability result.
     * Only real UUIDs qualify. Unknown capabilities yield an empty list.
     * Never reads model prose, only structured tool content.
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
     * Working-state candidate id/title pairs for list-shaped capabilities.
     * Single-result and unknown capabilities yield an empty list so callers
     * leave continuity state untouched, exactly as before.
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
     * Direct project id for single-result capabilities, or null when absent
     * or the shape is not single-result. Mirrors prior resolution semantics.
     */
    public static String directProjectId(String capabilityId, java.util.Map<String, Object> content) {
        ResultShape shape = resultShapeOf(capabilityId);
        if (shape == null || shape.listKey() != null || content == null) return null;
        Object id = content.get(FIELD_ID);
        return id == null ? null : String.valueOf(id);
    }
}
