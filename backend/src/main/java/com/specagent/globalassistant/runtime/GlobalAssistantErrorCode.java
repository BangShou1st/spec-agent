package com.specagent.globalassistant.runtime;

/**
 * Typed public error codes. Never stack traces / SQL / provider internals.
 */
public final class GlobalAssistantErrorCode {
    private GlobalAssistantErrorCode() {
    }
    public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
    public static final String TOOL_ARGUMENT_INVALID = "TOOL_ARGUMENT_INVALID";
    public static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String MODEL_INVALID_RESPONSE = "MODEL_INVALID_RESPONSE";
    public static final String RUN_STEP_LIMIT = "RUN_STEP_LIMIT";
    public static final String RUN_CANCELLED = "RUN_CANCELLED";
    public static final String RUN_ACTIVE = "GLOBAL_ASSISTANT_RUN_ACTIVE";
    public static final String RUN_INTERRUPTED = "RUN_INTERRUPTED";
    public static final String STEER_PENDING = "GLOBAL_ASSISTANT_STEER_PENDING";
    public static final String THREAD_ACTIVE = "GLOBAL_ASSISTANT_THREAD_ACTIVE";
    public static final String RUN_STALE = "GLOBAL_ASSISTANT_RUN_STALE";
}
