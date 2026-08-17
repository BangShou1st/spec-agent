package com.specagent.model.provider;

/**
 * Diagnostic failure raised by the OpenCode Zen transport and gateway.
 *
 * <p>Messages never contain the API key or the Authorization header value, so
 * errors can be logged or persisted safely.
 */
public class OpenCodeModelException extends RuntimeException {

    private final OpenCodeModelErrorCategory category;
    private final Integer httpStatus;

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message) {
        this(category, message, null, null);
    }

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message, Integer httpStatus) {
        this(category, message, httpStatus, null);
    }

    public OpenCodeModelException(OpenCodeModelErrorCategory category, String message, Throwable cause) {
        this(category, message, null, cause);
    }

    private OpenCodeModelException(OpenCodeModelErrorCategory category,
                                   String message,
                                   Integer httpStatus,
                                   Throwable cause) {
        super(message, cause);
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public OpenCodeModelErrorCategory category() {
        return category;
    }

    /**
     * The HTTP status that caused the failure, or null when the failure did not
     * reach the HTTP layer (timeout, connection, invalid response).
     */
    public Integer httpStatus() {
        return httpStatus;
    }
}