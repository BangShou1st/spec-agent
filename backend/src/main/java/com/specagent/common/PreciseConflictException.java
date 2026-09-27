package com.specagent.common;

/**
 * 文件名:PreciseConflictException.java
 *
 * 用途:携带精确、稳定原因码的状态冲突异常的标记基类,让客户端能针对
 * 具体被违反的规则做出处理。
 *
 * 【契约】。{@code PreciseConflictException} 必须由 HTTP 层以各自精确的
 * {@code 409} 错误码呈现(例如 {@code NO_ACTIVE_ROUTE}、
 * {@code ROUTE_NOT_OPEN}、{@code UNANSWERED_QUESTION_HAS_CHILD}、
 * {@code RELATION_DEPENDENCY_CYCLE}),绝不能被降级成通用的
 * {@code RUNTIME_CONFLICT} 报文——这个类型存在的意义就是让客户端能针对
 * 具体被违反的规则采取行动。
 *
 * 【为什么需要基类】。命令类端点的异常统一经由
 * {@link com.specagent.workspace.route.CommandExecution#execute} 转发,其
 * catch 分支按类型匹配。若只 catch {@code IllegalStateException}(状态冲突
 * 的天然父类型),会把动作内部抛出的每一个精确冲突都悄悄吞掉。这个基类给
 * 该包装层提供一个稳定、面向未来的挂钩:
 *
 * catch (PreciseConflictException ex) { throw ex; }   // 保留精确的 409
 * catch (IllegalStateException ex)    { ... }          // 通用 409
 *
 * 【新增异常的规则】。每一个新的"精确冲突"异常——即必须以自己的错误码
 * 送达客户端的状态前置条件失败——都必须(直接或间接)继承本类,并且必须由
 * {@code ApiExceptionHandler} 映射出自己的原因码。有一条架构测试强制这套
 * 命名/类型约定,避免未来新增的异常悄悄回落到 {@code RUNTIME_CONFLICT}。
 *
 * 继承 {@link IllegalStateException} 是为了给把这些失败归为状态冲突的
 * 既有调用方和测试保持历史契约。
 */
public abstract class PreciseConflictException extends IllegalStateException {

    protected PreciseConflictException(String message) {
        super(message);
    }

    protected PreciseConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
