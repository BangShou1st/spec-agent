package com.specagent.model.provider;

import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.gateway.ModelGatewayException;

/**
 * Diagnostic failure raised by the OpenCode Zen transport and gateway.
 *
 * <p>Messages never contain the API key or the Authorization header value, so
 * errors can be logged or persisted safely. The exception extends the
 * provider-neutral {@link ModelGatewayException}: the agent reasoning layer
 * catches the base type and reads {@link #gatewayCategory()}, while OpenCode
 * tests keep using the provider-specific {@link #category()}.
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
