package com.specagent.answer;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable user answer to a node.
 *
 * <p>Once finalized for a given {@code (routeId, nodeId)} pair, it must never be
 * overwritten. Re-answering creates a new route, replacement node, or answer
 * revision rather than mutating this record.
 */
public class Answer {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID nodeId;
    private final String selectedOptionId;
    private final String freeText;
    private final String createdByUser;
    private final Instant createdAt;

    public Answer(UUID id,
                  UUID projectId,
                  UUID routeId,
                  UUID nodeId,
                  String selectedOptionId,
                  String freeText,
                  String createdByUser,
                  Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.nodeId = nodeId;
        this.selectedOptionId = selectedOptionId;
        this.freeText = freeText;
        this.createdByUser = createdByUser;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID nodeId() {
        return nodeId;
    }

    public String selectedOptionId() {
        return selectedOptionId;
    }

    public String freeText() {
        return freeText;
    }

    public String createdByUser() {
        return createdByUser;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
