package com.specagent.context;

/**
 * The kind of operation a context snapshot was built for.
 *
 * <p>Operation mechanics only; never encodes a business domain.
 */
public enum ContextOperationType {
    NORMAL,
    REGENERATE,
    FORK,
    RESTORE,
    GENERATE_SPEC,
    INITIAL;

    public String code() {
        return name().toLowerCase();
    }

    public static ContextOperationType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Context operation type code must not be null");
        }
        return ContextOperationType.valueOf(code.toUpperCase());
    }
}
