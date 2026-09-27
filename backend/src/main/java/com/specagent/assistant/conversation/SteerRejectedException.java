package com.specagent.assistant.conversation;

/**
 * 文件名:SteerRejectedException.java
 *
 * 用途:类型化的 Steer 拒绝异常,携带机器可读的拒绝原因
 * (内容为空/超长、Run 不存在、线程不匹配、目标已过期、非法等)。
 *
 * 角色:conversation 包的类型化领域异常。拒绝原因在领域层就判定
 * 好,API 边界只做透传,不做任何消息文本解析。
 */
public class SteerRejectedException extends RuntimeException {
    public enum Reason {
        BLANK,
        TOO_LONG,
        RUN_NOT_FOUND,
        THREAD_MISMATCH,
        STALE_TARGET,
        INVALID
    }

    private final Reason reason;

    public SteerRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
