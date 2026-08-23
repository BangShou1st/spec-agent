package com.specagent.node;

/**
 * Stable outer classification of a workspace-unit node.
 *
 * <p>The kind set is intentionally small and stable; product variation goes
 * into {@code subtype} and {@code content}, not into new kinds. Legacy rows
 * predating the generic workspace model are interpreted as {@code INTERACTION}.
 */
public enum NodeKind {

    KNOWLEDGE("KNOWLEDGE"),
    INTERACTION("INTERACTION"),
    RESOURCE("RESOURCE"),
    ARTIFACT("ARTIFACT");

    private final String code;

    NodeKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeKind fromCode(String code) {
        if (code == null) {
            return INTERACTION;
        }
        for (NodeKind kind : values()) {
            if (kind.code.equals(code.trim().toUpperCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown node kind: " + code);
    }
}
