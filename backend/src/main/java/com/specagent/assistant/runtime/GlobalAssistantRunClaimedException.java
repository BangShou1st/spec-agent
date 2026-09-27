package com.specagent.assistant.runtime;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
/**
 * 文件名:GlobalAssistantRunClaimedException.java
 *
 * 用途:表示该 run 已被其他执行方认领或已进入终态——第二个派发者
 * 收到此异常时必须立即停止,不能发出相互矛盾的事件。
 */
public class GlobalAssistantRunClaimedException extends RuntimeException {
    private final GlobalAssistantRunStatus status;
    public GlobalAssistantRunClaimedException(GlobalAssistantRunStatus status) {
        super("Run is not available for execution: " + status);
        this.status = status;
    }
    public GlobalAssistantRunStatus status() {
        return status;
    }
}
