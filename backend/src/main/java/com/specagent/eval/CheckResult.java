package com.specagent.eval;

/** One deterministic check outcome (invariant or required property). */
public record CheckResult(String name, boolean passed, String detail) {
}
