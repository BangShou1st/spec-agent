package com.specagent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable semantic evidence for one AgentRun/attempt. */
public final class SemanticTrace {

    public static final String SCHEMA_VERSION = "semantic-trace.v1";

    private final UUID attemptId;
    private final Map<String, Map<String, Object>> stages;
    private final Map<String, Object> metadata;

    private SemanticTrace(UUID attemptId,
                          Map<String, Map<String, Object>> stages,
                          Map<String, Object> metadata) {
        this.attemptId = attemptId;
        this.stages = copyStages(stages);
        this.metadata = copyMap(metadata);
    }

    public static SemanticTrace empty(UUID attemptId) {
        return new SemanticTrace(attemptId, Map.of(), Map.of());
    }

    public UUID attemptId() {
        return attemptId;
    }

    public Map<String, Map<String, Object>> stages() {
        return stages;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public boolean isEmpty() {
        return stages.isEmpty() && metadata.isEmpty();
    }

    public SemanticTrace withStage(String name, Map<String, Object> value) {
        Map<String, Map<String, Object>> next = new LinkedHashMap<>(stages);
        next.put(name, castMap(SemanticTraceSanitizer.sanitize(value)));
        return new SemanticTrace(attemptId, next, metadata);
    }

    public SemanticTrace withStageValue(String name, String key, Object value) {
        Map<String, Object> stage = new LinkedHashMap<>(stages.getOrDefault(name, Map.of()));
        stage.put(key, SemanticTraceSanitizer.sanitize(value));
        return withStage(name, stage);
    }

    public SemanticTrace withMetadata(Map<String, Object> values) {
        Map<String, Object> next = new LinkedHashMap<>(metadata);
        next.putAll(values == null ? Map.of() : values);
        return new SemanticTrace(attemptId, stages, next);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", SCHEMA_VERSION);
        map.put("attempt_id", attemptId == null ? null : attemptId.toString());
        map.put("correlation_id", attemptId == null ? null : attemptId.toString());
        map.put("metadata", metadata);
        map.put("stages", stages);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static SemanticTrace fromMap(Map<String, Object> map) {
        if (map == null) {
            return empty(null);
        }
        UUID attemptId = null;
        Object rawId = map.get("attempt_id");
        if (rawId != null) {
            try {
                attemptId = UUID.fromString(String.valueOf(rawId));
            } catch (IllegalArgumentException ignored) {
                // A malformed diagnostic id is retained only as absent; the
                // evaluation artifact remains readable and fail-closed.
            }
        }
        Map<String, Map<String, Object>> stages = new LinkedHashMap<>();
        Object rawStages = map.get("stages");
        if (rawStages instanceof Map<?, ?> stageMap) {
            for (Map.Entry<?, ?> entry : stageMap.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    stages.put(String.valueOf(entry.getKey()), castMap(value));
                }
            }
        }
        Object rawMetadata = map.get("metadata");
        return new SemanticTrace(attemptId, stages,
                rawMetadata instanceof Map<?, ?> value ? castMap(value) : Map.of());
    }

    private static Map<String, Map<String, Object>> copyStages(
            Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, copyMap(value)));
        }
        return Map.copyOf(copy);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
