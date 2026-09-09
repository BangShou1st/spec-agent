package com.specagent.globalassistant.conversation;

/**
 * A second active run was requested for a thread that already hosts one.
 * Maps to HTTP 409 with code GLOBAL_ASSISTANT_RUN_ACTIVE.
 */
public class GlobalAssistantRunActiveException extends RuntimeException {
    public static final String CODE = "GLOBAL_ASSISTANT_RUN_ACTIVE";
    public GlobalAssistantRunActiveException(String message) {
        super(message);
    }
    public GlobalAssistantRunActiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
