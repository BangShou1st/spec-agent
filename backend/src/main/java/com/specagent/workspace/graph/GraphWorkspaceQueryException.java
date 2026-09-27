package com.specagent.workspace.graph;

/**
 * 文件名:GraphWorkspaceQueryException.java
 *
 * 用途:图工作区读取侧的统一失败异常,与具体读模型解耦。
 *
 * 读模型/应用层不能依赖外层 HTTP API,因此可预期的查询失败用这个
 * 封闭的 Reason 表达,而不是抛 API 层异常。API 边界再把 Reason 翻译成
 * 稳定的 HTTP 契约(404 {@code PROJECT_NOT_FOUND}、500
 * {@code INTERNAL_INVARIANT_VIOLATION})。
 *
 * 消息是静态且安全的:绝不携带密钥、原始持久化数据或 provider
 * 载荷,API 边界也绝不把它们回显给客户端。
 */
public class GraphWorkspaceQueryException extends RuntimeException {

    /** 图工作区读取失败原因的封闭枚举。 */
    public enum Reason {
        /** 请求的项目不存在。 */
        PROJECT_NOT_FOUND,
        /** 图未通过完整性/归属不变量校验。 */
        INVARIANT_VIOLATION
    }

    private final Reason reason;

    private GraphWorkspaceQueryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static GraphWorkspaceQueryException of(Reason reason, String message) {
        return new GraphWorkspaceQueryException(reason, message);
    }

    public Reason reason() {
        return reason;
    }
}
