package com.specagent.agent.decision;

import java.util.List;

/**
 * 文件名:ReflectionResult.java
 *
 * 用途:对节点草稿或回答补丁草稿执行 Reflection 任务的结果。
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