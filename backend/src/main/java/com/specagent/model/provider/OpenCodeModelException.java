package com.specagent.model.provider;

import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.ModelGatewayException;

/**
 * 文件名:OpenCodeModelException.java
 *
 * 用途:OpenCode Zen 传输层与网关抛出的带诊断信息的失败异常。消息中永不包含
 * API key 或 Authorization 头的值,因此可以安全地记录日志或持久化。该异常继承
 * 提供商无关的 {@link ModelGatewayException}:agent 推理层捕获基类并读取
 * {@link #gatewayCategory()},而 OpenCode 的测试代码继续使用提供商专属的
 * {@link #category()}。
 */
public class OpenCodeModelException extends ModelGatewayException {

    private final OpenCodeModelErrorCategory category;
    private final OpenCodeFailureDiagnostics diagnostics;

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message) {
        this(category, message, null, null);
    }

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message, Integer httpStatus) {
        this(category, message, httpStatus, null);
    }

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message, Throwable cause) {
        this(category, message, null, cause);
    }

    public OpenCodeModelException(OpenCodeModelErrorCategory category,
                                  String message,
                                  Integer httpStatus,
                                  Throwable cause) {
        this(category, message, httpStatus, cause, OpenCodeFailureDiagnostics.empty());
    }

    private OpenCodeModelException(OpenCodeModelErrorCategory category,
                                   String message,
                                   Integer httpStatus,
                                   Throwable cause,
                                   OpenCodeFailureDiagnostics diagnostics) {
        super(toGatewayCategory(category), message, httpStatus, cause);
        this.category = category;
        this.diagnostics = diagnostics == null ? OpenCodeFailureDiagnostics.empty() : diagnostics;
    }

    public OpenCodeModelErrorCategory category() {
        return category;
    }

    public OpenCodeFailureDiagnostics diagnostics() {
        return diagnostics;
    }

    public OpenCodeModelException withDiagnostics(OpenCodeFailureDiagnostics nextDiagnostics) {
        return new OpenCodeModelException(category(), getMessage(), httpStatus(), getCause(), nextDiagnostics);
    }

    private static ModelGatewayErrorCategory toGatewayCategory(OpenCodeModelErrorCategory category) {
        return switch (category) {
            case TIMEOUT -> ModelGatewayErrorCategory.TIMEOUT;
            case CONNECTION -> ModelGatewayErrorCategory.CONNECTION;
            case AUTHENTICATION -> ModelGatewayErrorCategory.AUTHENTICATION;
            case RATE_LIMITED -> ModelGatewayErrorCategory.RATE_LIMITED;
            case SERVER_ERROR -> ModelGatewayErrorCategory.SERVER_ERROR;
            case PROVIDER_REQUEST_ERROR -> ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR;
            case INVALID_RESPONSE -> ModelGatewayErrorCategory.INVALID_RESPONSE;
            case EMPTY_CONTENT -> ModelGatewayErrorCategory.EMPTY_CONTENT;
            case INVALID_MODEL -> ModelGatewayErrorCategory.INVALID_MODEL;
            case NOT_CONFIGURED -> ModelGatewayErrorCategory.NOT_CONFIGURED;
        };
    }
}
