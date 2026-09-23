package com.specagent.eval;

/** One typed evaluation violation with a human-readable detail. */
public record Violation(FailureClass failureClass, String detail) {
}
