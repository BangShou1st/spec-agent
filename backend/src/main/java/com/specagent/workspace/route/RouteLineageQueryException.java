package com.specagent.workspace.route;

/**
 * 文件名:RouteLineageQueryException.java
 *
 * 用途:路线 lineage 读取失败时抛出的、与读模型无关的异常。读模型/
 * 应用层不允许依赖外层 HTTP API 层,因此预期内的查询失败用这个封闭
 * reason 集合表达,而不是 API 异常;API 边界再把 reason 翻译成稳定的
 * HTTP 契约(404 {@code PROJECT_NOT_FOUND}、404 {@code ROUTE_NOT_FOUND}、
 * 500 {@code INTERNAL_INVARIANT_VIOLATION})。
 *
 * 消息是静态且安全的:绝不携带机密、原始持久化数据或 provider 负载,
 * API 边界也绝不把它们回显给客户端。
 */
public class RouteLineageQueryException extends RuntimeException {

    /** 路线 lineage 读取失败原因的封闭、强边界枚举。 */
    public enum Reason {
        /** 请求的项目不存在。 */
        PROJECT_NOT_FOUND,
        /** 请求的路线不存在,或不属于该项目。 */
        ROUTE_NOT_FOUND,
        /** 路线 lineage 未通过完整性/归属不变量检查。 */
        INVARIANT_VIOLATION
    }

    private final Reason reason;

    private RouteLineageQueryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RouteLineageQueryException of(Reason reason, String message) {
        return new RouteLineageQueryException(reason, message);
    }

    public Reason reason() {
        return reason;
    }
}
