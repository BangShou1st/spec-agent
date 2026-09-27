package com.specagent.agent.runtime;

import com.specagent.common.PreciseConflictException;

/**
 * 文件名:RouteTargetConflictException.java
 *
 * 用途:当新 run 无法把目标指向其 route 时抛出——项目的运行时 route 状态
 * 不允许:项目没有活跃 route,或所请求的 route 已不再是 OPEN。携带稳定的
 * reason code,让 API 层能返回精确的 409,而不是笼统的运行时冲突。
 *
 * 继承 {@link PreciseConflictException}(后者本身是
 * {@code IllegalStateException}):该冲突本质是状态前置条件不满足,因此原有
 * 按 {@code IllegalStateException("no active route")} 分类识别的调用方/测试
 * 仍然匹配;而 API 层可以把这个具体类型映射为精确的 409,
 * {@code CommandExecution} 也会原样保留它,而不是降级为
 * {@code RUNTIME_CONFLICT}。
 */
public class RouteTargetConflictException extends PreciseConflictException {

    public enum Reason {
        /** 项目没有活跃的 route 指针。 */
        NO_ACTIVE_ROUTE,
        /** 所请求的 route 存在,但其生命周期状态不是 OPEN。 */
        ROUTE_NOT_OPEN
    }

    private final Reason reason;

    public RouteTargetConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
