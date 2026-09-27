package com.specagent.workspace.route;

/**
 * 文件名:RouteLifecycleStatus.java
 *
 * 用途:路线生命周期状态枚举。
 *
 * 注意:{@code active} 不是一个生命周期状态。当前工作路线由
 * {@code Project.activeRouteId} 表达;一条路线可以是 {@code open},
 * 但并不一定是活跃路线。
 */
public enum RouteLifecycleStatus {
    OPEN,
    SUPERSEDED,
    ARCHIVED,
    DELETED;

    public String code() {
        return name().toLowerCase();
    }

    public static RouteLifecycleStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Route lifecycle status code must not be null");
        }
        return RouteLifecycleStatus.valueOf(code.toUpperCase());
    }
}
