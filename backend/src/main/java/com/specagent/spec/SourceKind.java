package com.specagent.spec;

/**
 * The kind of runtime record a spec claim traces back to.
 *
 * <p>Every confirmed spec claim must reference at least one source record. This
 * is domain-neutral provenance, not business-domain classification.
 */
public enum SourceKind {
    NODE,
    ANSWER,
    PATCH,
    CONTEXT,
    ROUTE;

    public String code() {
        return name().toLowerCase();
    }

    public static SourceKind fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Source kind code must not be null");
        }
        return SourceKind.valueOf(code.toUpperCase());
    }
}
