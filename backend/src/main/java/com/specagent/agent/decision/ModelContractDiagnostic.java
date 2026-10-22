package com.specagent.agent.decision;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Safe, bounded metadata for a verified model/Brain response contract failure. */
public record ModelContractDiagnostic(
        String diagnosticId,
        String stage,
        String validatorId,
        String machineCode,
        String fieldPath,
        String expectedType,
        String actualType,
        String boundary,
        Boolean javaWireValidatorEntered,
        Boolean executorReached,
        String sourceExceptionType) {

    private static final Set<String> MACHINE_CODES = Set.of(
            "MODEL_OUTPUT_INVALID_JSON",
            "MODEL_OUTPUT_SCHEMA_INVALID",
            "MODEL_OUTPUT_AMBIGUOUS_SOURCE_REFS",
            "MODEL_OUTPUT_UNGROUNDED_REFERENCE",
            "MODEL_OUTPUT_CONFLICT_NOT_SURFACED",
            "JAVA_WIRE_UNKNOWN_FIELD",
            "JAVA_WIRE_TYPE_MISMATCH",
            "JAVA_WIRE_CONTRACT_INVALID",
            "JAVA_RESPONSE_CONTRACT_INVALID");
    private static final Set<String> BOUNDARIES = Set.of(
            "PYTHON_BRAIN", "JAVA_WIRE", "JAVA_RESPONSE_VALIDATOR", "UNKNOWN");
    private static final Set<String> TYPES = Set.of(
            "string", "integer", "number", "boolean", "object", "array", "null", "uuid",
            "missing", "invalid_json", "present", "unknown",
            "allowed source reference", "equivalent source reference lists",
            "different source reference lists", "non-empty array", "empty array",
            "no additional property");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,79}");
    private static final Pattern SAFE_PATH = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0,63}(?:\\.[A-Za-z_][A-Za-z0-9_]{0,63}|\\[[0-9]{1,5}]){0,15}");

    public ModelContractDiagnostic {
        diagnosticId = safeUuid(diagnosticId);
        stage = safeToken(stage);
        validatorId = safeToken(validatorId);
        machineCode = machineCode != null && MACHINE_CODES.contains(machineCode)
                ? machineCode : "UNKNOWN";
        fieldPath = fieldPath != null && SAFE_PATH.matcher(fieldPath).matches()
                ? fieldPath : "UNKNOWN";
        expectedType = expectedType != null && TYPES.contains(expectedType)
                ? expectedType : "unknown";
        actualType = actualType != null && TYPES.contains(actualType)
                ? actualType : "unknown";
        boundary = boundary != null && BOUNDARIES.contains(boundary)
                ? boundary : "UNKNOWN";
        sourceExceptionType = safeToken(sourceExceptionType);
    }

    private static String safeUuid(String value) {
        if (value == null) return "UNKNOWN";
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ignored) {
            return "UNKNOWN";
        }
    }

    private static String safeToken(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : "UNKNOWN";
    }
}
