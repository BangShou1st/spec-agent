package com.specagent.route;

/** Durable reason a route diverged from another route. */
public enum RouteBranchType {
    FORK,
    REANSWER,
    REGENERATE,
    /** Free continuation from a non-tip node (free continuation branching). */
    CONTINUATION;

    public static RouteBranchType fromCode(String code) {
        return code == null ? null : valueOf(code.toUpperCase());
    }

    public String code() {
        return name().toLowerCase();
    }
}
