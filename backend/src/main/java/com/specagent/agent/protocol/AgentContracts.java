package com.specagent.agent.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.specagent.common.Json;

/**
 * 文件名:AgentContracts.java
 *
 * 用途:跨语言契约(Java ↔ Python)的严格 JSON 读写入口。
 *
 * 约束:这里的 mapper 刻意比应用默认 mapper 更严格——未知属性、
 * 歧义枚举、给基本类型传 null 全部 fail-closed。请求信封(Java 构建)
 * 和响应信封(Brain 构建、不可信)都必须经过它,保证 golden fixtures
 * 在边界两侧行为一致。
 */
public final class AgentContracts {

    private static final java.util.Set<String> WIRE_FIELD_NAMES = java.util.Set.of(
            "protocolVersion", "runId", "stateUpdate", "claims", "kind", "text",
            "status", "confidence", "sourceRefs", "observation", "known", "unknowns",
            "conflicts", "risks", "actionProposal", "actionFamily", "payload",
            "proposalId", "idempotencyKey", "baseContextSnapshotId", "baseContextHash",
            "anchorRefs", "usage", "modelCalls", "promptHashes", "diagnostics",
            "selectedEligibilityVersion", "selectedEligibilityBasisHash",
            "eligibilityEvidenceRefs", "artifact", "artifactType", "sections", "title",
            "content", "unresolvedItems", "capabilityId", "arguments", "questionText",
            "options", "label", "allowFreeAnswer", "subtype", "relationClass",
            "relationType", "sourceRef", "targetRef");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Stable on first construction and after JSON replay, including nested location/provenance maps.
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final Json JSON = new Json(STRICT_MAPPER);

    private AgentContracts() {
    }

    /** 按带版本的线上(wire)字段规则序列化契约值。 */
    public static String write(Object value) {
        return JSON.write(value);
    }

    /**
     * 以 fail-closed 方式解析契约值。任何未知字段、未知枚举或形状违约
     * 都会抛出 {@link AgentContractException}。
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return JSON.read(json, type);
        } catch (IllegalStateException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof com.fasterxml.jackson.databind.DatabindException databindEx) {
                WireDiagnostic diagnostic = wireDiagnostic(databindEx);
                throw new AgentContractException(
                        "Contract violation in " + type.getSimpleName(),
                        diagnostic.code(), diagnostic.fieldPath(), diagnostic.expectedType(),
                        diagnostic.actualType(), "AgentContracts.read",
                        databindEx.getClass().getSimpleName());
            }
            if (cause instanceof AgentContractException contract) {
                throw new AgentContractException(
                        "Contract violation in " + type.getSimpleName(),
                        "JAVA_WIRE_CONTRACT_INVALID", null, "unknown", "unknown",
                        type.getSimpleName(), contract.sourceExceptionType() == null
                                ? contract.getClass().getSimpleName()
                                : contract.sourceExceptionType());
            }
            throw new AgentContractException(
                    "Contract violation in " + type.getSimpleName(),
                    "JAVA_WIRE_CONTRACT_INVALID", null, "unknown", "unknown",
                    type.getSimpleName(), cause == null
                            ? "IllegalStateException" : cause.getClass().getSimpleName());
        }
    }

    private static WireDiagnostic wireDiagnostic(DatabindException failure) {
        if (!(failure instanceof JsonMappingException mappingFailure)) {
            return new WireDiagnostic("JAVA_WIRE_CONTRACT_INVALID", null,
                    "unknown", "unknown");
        }
        if (mappingFailure instanceof UnrecognizedPropertyException) {
            String path = parentPathOf(mappingFailure.getPath());
            String fullPath = path.isBlank() ? "UNKNOWN_FIELD" : path + ".UNKNOWN_FIELD";
            return new WireDiagnostic("JAVA_WIRE_UNKNOWN_FIELD", fullPath,
                    "no additional property", "present");
        }
        String path = pathOf(mappingFailure.getPath());
        if (mappingFailure instanceof MismatchedInputException mismatch) {
            Object processor = mismatch.getProcessor();
            String actual = processor instanceof JsonParser parser
                    ? actualType(parser.currentToken()) : "unknown";
            return new WireDiagnostic("JAVA_WIRE_TYPE_MISMATCH", path,
                    expectedType(mismatch.getTargetType()), actual);
        }
        return new WireDiagnostic("JAVA_WIRE_CONTRACT_INVALID", path,
                "unknown", "unknown");
    }

    private static String pathOf(java.util.List<JsonMappingException.Reference> path) {
        return pathOf(path, path.size());
    }

    private static String parentPathOf(java.util.List<JsonMappingException.Reference> path) {
        return pathOf(path, Math.max(0, path.size() - 1));
    }

    private static String pathOf(java.util.List<JsonMappingException.Reference> path, int count) {
        StringBuilder value = new StringBuilder();
        for (JsonMappingException.Reference reference : path.subList(0, count)) {
            if (reference.getFieldName() != null) {
                String segment = safeSegment(reference.getFieldName());
                if (!value.isEmpty()) value.append('.');
                value.append(segment);
            } else if (reference.getIndex() >= 0) {
                value.append('[').append(reference.getIndex()).append(']');
            }
        }
        return value.toString();
    }

    private static String safeSegment(String value) {
        return value != null && WIRE_FIELD_NAMES.contains(value)
                ? value : "UNKNOWN_FIELD";
    }

    private static String expectedType(Class<?> type) {
        if (type == null) return "unknown";
        if (String.class.isAssignableFrom(type)) return "string";
        if (java.util.UUID.class.isAssignableFrom(type)) return "uuid";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return "number";
        if (java.util.Collection.class.isAssignableFrom(type)) return "array";
        if (java.util.Map.class.isAssignableFrom(type)) return "object";
        return "unknown";
    }

    private static String actualType(JsonToken token) {
        if (token == null) return "unknown";
        return switch (token) {
            case VALUE_STRING -> "string";
            case VALUE_NUMBER_INT -> "integer";
            case VALUE_NUMBER_FLOAT -> "number";
            case VALUE_TRUE, VALUE_FALSE -> "boolean";
            case VALUE_NULL -> "null";
            case START_OBJECT -> "object";
            case START_ARRAY -> "array";
            default -> "unknown";
        };
    }

    private record WireDiagnostic(String code, String fieldPath,
                                  String expectedType, String actualType) {
    }
}
