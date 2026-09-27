package com.specagent.assistant;

/**
 * 文件名:GlobalAssistantErrorCode.java
 *
 * 用途:定义全局助手对外暴露的公共错误码常量。所有返回给前端的错误都
 * 必须使用这里的类型化错误码,绝不把堆栈、SQL 或模型提供方内部细节泄露出去。
 *
 * 角色:位于包根,是被 runtime / conversation / api 各层共用的错误码字典,
 * 保证了错误码字符串只有一处定义来源。
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
