package com.specagent.assistant.model;

/**
 * 文件名:GlobalAssistantModelException.java
 *
 * 用途:模型链路的类型化失败异常,携带稳定的对外错误码
 * (如 MODEL_UNAVAILABLE、MODEL_INVALID_RESPONSE),供上层映射为
 * 统一错误响应;运行时靠它把"模型侧的问题"与其他失败区分开。
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
