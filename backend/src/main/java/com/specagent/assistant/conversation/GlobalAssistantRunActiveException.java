package com.specagent.assistant.conversation;

/**
 * 文件名:GlobalAssistantRunActiveException.java
 *
 * 用途:当某个线程已经承载一个活跃 Run,又对它请求第二个活跃 Run 时抛出。
 *
 * 角色:conversation 包的类型化领域异常,由上层映射为
 * HTTP 409,错误码为 GLOBAL_ASSISTANT_RUN_ACTIVE。
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
