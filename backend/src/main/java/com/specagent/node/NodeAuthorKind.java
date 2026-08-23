package com.specagent.node;

/**
 * Who created a node. Provenance is runtime-owned and never inferred from
 * model output.
 */
public enum NodeAuthorKind {

    USER("USER"),
    AGENT("AGENT"),
    RUNTIME("RUNTIME");

    private final String code;

    NodeAuthorKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeAuthorKind fromCode(String code) {
        if (code == null) {
            return AGENT;
        }
        for (NodeAuthorKind kind : values()) {
            if (kind.code.equals(code.trim().toUpperCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown node author kind: " + code);
    }
}
