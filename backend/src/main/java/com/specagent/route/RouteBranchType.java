package com.specagent.route;

/** Durable reason a route diverged from another route. */
public enum RouteBranchType {
    FORK,
    REANSWER,
    REGENERATE,
    /** Free continuation from a non-tip node (free continuation branching). */
    CONTINUATION,
    /**
     * Resume a historical unanswered Question on an explicit source route. The
     * route-only branch reuses the existing canonical Question; Undo must
     * never retract that Question or delete any answer.
     */
    RESUME_QUESTION;

    public static RouteBranchType fromCode(String code) {
        return code == null ? null : valueOf(code.toUpperCase());
    }

    public String code() {
        return name().toLowerCase();
    }
}
