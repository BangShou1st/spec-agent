package com.specagent.route;

/**
 * Route lifecycle status.
 *
 * <p>Note: {@code active} is NOT a lifecycle status. The current working route
 * is expressed by {@code Project.activeRouteId}. A route may be {@code open}
 * without being the active route.
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
