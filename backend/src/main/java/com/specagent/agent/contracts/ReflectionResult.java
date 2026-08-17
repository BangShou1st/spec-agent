package com.specagent.agent.contracts;

import java.util.List;

/**
 * Result of a reflection task over a node draft or an answer patch draft.
 */
public record ReflectionResult(
        boolean accepted,
        List<String> errors,
        List<String> warnings
) {
    public ReflectionResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ReflectionResult acceptedResult() {
        return new ReflectionResult(true, List.of(), List.of());
    }

    public static ReflectionResult rejectedResult(String error) {
        return new ReflectionResult(false, List.of(error), List.of());
    }
}