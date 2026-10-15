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
    /** 执行失败(框架或宿主侧)。既有 GaExecutionCompletion 已在线级使用该字面量。 */
    public static final String GA_EXECUTION_FAILED = "GA_EXECUTION_FAILED";
    /** Python 执行服务不可达;面向用户可区分的可操作类别。 */
    public static final String GA_PYTHON_UNAVAILABLE = "GA_PYTHON_UNAVAILABLE";
    /**
     * GA 执行协议版本不兼容(design §8"显式版本化,不默认允许旧请求"):
     * Java 所需的执行信封/事件版本不在 Python /health 公告的支持列表内。
     * 这是部署错误(两端版本不同步),如实暴露,绝不默认放行。
     */
    public static final String GA_PROTOCOL_MISMATCH = "GA_PROTOCOL_MISMATCH";
    /**
     * 会话派生记忆依赖的项目已撤权或无法确认(第二阶段 §6.5):该轮执行在把历史
     * 装配进执行信封之前失败关闭,不向 Python/模型发送旧项目观察。
     */
    public static final String GA_SOURCE_REVOKED = "GA_SOURCE_REVOKED";
}
