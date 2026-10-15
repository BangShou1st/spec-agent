package com.specagent.access;

/**
 * 文件名:AccessDeniedException.java
 *
 * 用途:授权拒绝的显式信号。由授权判定抛出,由 HTTP 边界
 * ({@code com.specagent.web.ApiExceptionHandler})映射为稳定契约。
 *
 * 为什么不直接抛 Spring Security 的 AccessDeniedException:
 * 本项目要求"资源不可见 -> 404、可见但无权限 -> 403、登录失效 -> 401"
 * 三种语义分别可测,并且后台任务与内部 RPC 也要复用同一判定。若绑定
 * Spring 的异常类型,权限语义就被绑死在 HTTP 层,无法复用到 Worker。
 *
 * @param reasonCode 稳定原因码({@link AccessReason})
 * @param concealed  true 表示资源对主体<b>不可见</b>,对外必须表现为 404
 *                   而不是 403,避免泄露"存在但无权"这一事实
 */
public class AccessDeniedException extends RuntimeException {

    private final String reasonCode;
    private final boolean concealed;

    public AccessDeniedException(String reasonCode, boolean concealed, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.concealed = concealed;
    }

    public static AccessDeniedException notVisible(String message) {
        return new AccessDeniedException(AccessReason.RESOURCE_NOT_VISIBLE, true, message);
    }

    public static AccessDeniedException forbidden(String reasonCode, String message) {
        return new AccessDeniedException(reasonCode, false, message);
    }

    public String reasonCode() {
        return reasonCode;
    }

    public boolean concealed() {
        return concealed;
    }
}
