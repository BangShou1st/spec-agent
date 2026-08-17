package com.specagent.node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable clarification prompt in the exploration tree.
 *
 * <p>The {@code question}, {@code purpose}, and {@code options} are fixed at
 * creation and never edited. Regeneration creates a replacement node instead of
 * mutating this one.
 */
public class Node {

    private final UUID id;
    private final UUID projectId;
    private final UUID parentNodeId;
    private final UUID createdByRunId;
    private final UUID supersedesNodeId;
    private final String question;
    private final String purpose;
    private final List<NodeOption> options;
    private final boolean allowFreeAnswer;
    private final Instant createdAt;

    public Node(UUID id,
                UUID projectId,
                UUID parentNodeId,
                UUID createdByRunId,
                UUID supersedesNodeId,
                String question,
                String purpose,
                List<NodeOption> options,
                boolean allowFreeAnswer,
                Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.parentNodeId = parentNodeId;
        this.createdByRunId = createdByRunId;
        this.supersedesNodeId = supersedesNodeId;
        this.question = question;
        this.purpose = purpose;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.allowFreeAnswer = allowFreeAnswer;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID parentNodeId() {
        return parentNodeId;
    }

    public boolean isRoot() {
        return parentNodeId == null;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public UUID supersedesNodeId() {
        return supersedesNodeId;
    }

    public String question() {
        return question;
    }

    public String purpose() {
        return purpose;
    }

    public List<NodeOption> options() {
        return options;
    }

    public boolean allowFreeAnswer() {
        return allowFreeAnswer;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
