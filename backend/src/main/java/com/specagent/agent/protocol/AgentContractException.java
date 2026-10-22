package com.specagent.agent.protocol;

/**
 * 文件名:AgentContractException.java
 *
 * 用途:在 Java ↔ Python 边界任一侧检测到跨语言契约违约时抛出的异常,
 * 包括:未知协议版本、未知字段、模型/Brain 凭空捏造 Runtime 独有的 id、
 * 引用了不允许的 source ref,以及其他一切 fail-closed 拒绝场景。
 */
public class AgentContractException extends com.specagent.agent.protocol.ModelContractException {

    private final String diagnosticId;
    private final String diagnosticCode;
    private final String fieldPath;
    private final String expectedType;
    private final String actualType;
    private final String validatorId;
    private final String sourceExceptionType;

    public AgentContractException(String message) {
        this(message, null, null, null, null, null);
    }

    public AgentContractException(String message, String diagnosticCode, String fieldPath,
                                  String expectedType, String actualType, String validatorId) {
        this(message, diagnosticCode, fieldPath, expectedType, actualType, validatorId, null);
    }

    public AgentContractException(String message, String diagnosticCode, String fieldPath,
                                  String expectedType, String actualType, String validatorId,
                                  String sourceExceptionType) {
        super(message);
        this.diagnosticId = java.util.UUID.randomUUID().toString();
        this.diagnosticCode = diagnosticCode;
        this.fieldPath = fieldPath;
        this.expectedType = expectedType;
        this.actualType = actualType;
        this.validatorId = validatorId;
        this.sourceExceptionType = sourceExceptionType;
    }

    public String diagnosticId() {
        return diagnosticId;
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }

    public String fieldPath() {
        return fieldPath;
    }

    public String expectedType() {
        return expectedType;
    }

    public String actualType() {
        return actualType;
    }

    public String validatorId() {
        return validatorId;
    }

    public String sourceExceptionType() {
        return sourceExceptionType;
    }
}
