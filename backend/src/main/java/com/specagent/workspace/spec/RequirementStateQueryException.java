package com.specagent.workspace.spec;

/**
 * 文件名:RequirementStateQueryException.java
 *
 * 用途:需求状态查询读取失败时抛出的读模型中立异常。读模型/应用层不得依赖
 * 外层 HTTP API 层,因此预期内的查询失败用这个封闭的 reason 枚举表达,而不是
 * 抛 API 异常;API 边界再把 reason 翻译成稳定的 HTTP 契约(404
 * {@code PROJECT_NOT_FOUND}、404 {@code ROUTE_NOT_FOUND}、500
 * {@code INTERNAL_INVARIANT_VIOLATION})。
 *
 * 消息是静态且安全的:绝不携带机密、原始持久化数据或提供商负载,API 边界
 * 也绝不把它们回显给客户端。
 */
public class RequirementStateQueryException extends RuntimeException {

    /** 需求状态读取失败的封闭、强受限 reason 集合。 */
    public enum Reason {
        /** 请求的项目不存在。 */
        PROJECT_NOT_FOUND,
        /** 请求的 route 不存在,或不属于该项目。 */
        ROUTE_NOT_FOUND,
        /** 活跃 route 指针未能解析到该项目拥有的 route。 */
        INVARIANT_VIOLATION
    }

    private final Reason reason;

    private RequirementStateQueryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RequirementStateQueryException of(Reason reason, String message) {
        return new RequirementStateQueryException(reason, message);
    }

    public Reason reason() {
        return reason;
    }
}
