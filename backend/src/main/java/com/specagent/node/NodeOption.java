package com.specagent.node;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specagent.common.Ids;

import java.util.UUID;

/**
 * A selectable option presented on a clarification node.
 *
 * <p>Options are part of the immutable node prompt; they cannot be edited after
 * the node is created.
 */
public class NodeOption {

    private final UUID id;
    private final String label;
    private final String impact;

    @JsonCreator
    public NodeOption(@JsonProperty("id") UUID id,
                      @JsonProperty("label") String label,
                      @JsonProperty("impact") String impact) {
        this.id = id;
        this.label = label;
        this.impact = impact;
    }

    public static NodeOption of(String label, String impact) {
        return new NodeOption(Ids.random(), label, impact);
    }

    @JsonProperty("id")
    public UUID id() {
        return id;
    }

    @JsonProperty("label")
    public String label() {
        return label;
    }

    @JsonProperty("impact")
    public String impact() {
        return impact;
    }
}
