package com.specagent.node;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specagent.common.Ids;

import java.util.UUID;

/**
 * A selectable option presented on a clarification node.
 *
 * <p>Options are part of the immutable node prompt; they cannot be edited after
 * the node is created. {@code recommended} marks the model's context-based
 * suggestion — advice shown to the user, never a pre-selected answer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeOption {

    private final UUID id;
    private final String label;
    private final String impact;
    private final boolean recommended;

    @JsonCreator
    public NodeOption(@JsonProperty("id") UUID id,
                      @JsonProperty("label") String label,
                      @JsonProperty("impact") String impact) {
        this(id, label, impact, false);
    }

    public NodeOption(UUID id, String label, String impact, boolean recommended) {
        this.id = id;
        this.label = label;
        this.impact = impact;
        this.recommended = recommended;
    }

    public static NodeOption of(String label, String impact) {
        return new NodeOption(Ids.random(), label, impact, false);
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

    @JsonProperty("recommended")
    public boolean recommended() {
        return recommended;
    }
}
