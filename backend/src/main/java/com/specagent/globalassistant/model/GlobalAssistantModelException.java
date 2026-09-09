package com.specagent.globalassistant.model;

/**
 * Typed model failure with a stable public error code.
 */
public class GlobalAssistantModelException extends RuntimeException {
    private final String errorCode;
    public GlobalAssistantModelException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    public GlobalAssistantModelException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    public String errorCode() {
        return errorCode;
    }
}
