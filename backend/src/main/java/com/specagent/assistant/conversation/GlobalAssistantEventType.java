package com.specagent.assistant.conversation;

/**
 * 文件名:GlobalAssistantEventType.java
 *
 * 用途:冻结的公共事件类型常量表,定义 SSE 事件流上所有事件名的
 * 规范写法(RUN_STARTED、ASSISTANT_DELTA、TOOL_COMPLETED 等)。
 *
 * 角色:conversation 包的事件词汇表,前端与后端都只认这里的
 * 规范名称,不允许任何同义变体。
 */
public final class GlobalAssistantEventType {
    private GlobalAssistantEventType() {
    }
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String STATUS = "STATUS";
    public static final String ASSISTANT_DELTA = "ASSISTANT_DELTA";
    public static final String ANSWER_STREAM_STARTED = "ANSWER_STREAM_STARTED";
    public static final String ANSWER_DELTA = "ANSWER_DELTA";
    public static final String ANSWER_STREAM_RESET = "ANSWER_STREAM_RESET";
    public static final String TOOL_STARTED = "TOOL_STARTED";
    public static final String TOOL_COMPLETED = "TOOL_COMPLETED";
    public static final String TOOL_FAILED = "TOOL_FAILED";
    public static final String USER_INPUT_REQUIRED = "USER_INPUT_REQUIRED";
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String UI_ACTION = "UI_ACTION";
    public static final String ASSISTANT_COMPLETED = "ASSISTANT_COMPLETED";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String RUN_CANCELLED = "RUN_CANCELLED";
}
