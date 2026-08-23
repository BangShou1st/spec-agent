package com.specagent.graph;

/**
 * Semantic relation vocabulary between nodes.
 *
 * <p>Semantic relations are stored separately from visible continuation
 * lineage and are never rendered as default Canvas edges. Model-inferred
 * relations enter as Advisor proposals; confidence alone never turns an
 * inferred relation into durable fact.
 */
public enum NodeRelationType {

    RELATED_TO("RELATED_TO"),
    DEPENDS_ON("DEPENDS_ON"),
    DERIVED_FROM("DERIVED_FROM"),
    CONFLICTS_WITH("CONFLICTS_WITH"),
    SUPPORTS("SUPPORTS");

    private final String code;

    NodeRelationType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NodeRelationType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Relation type must not be null");
        }
        for (NodeRelationType type : values()) {
            if (type.code.equals(code.trim().toUpperCase())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown relation type: " + code);
    }
}
