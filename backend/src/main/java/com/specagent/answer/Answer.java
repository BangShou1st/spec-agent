package com.specagent.answer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable user answer to a node.
 *
 * <p>Once finalized for a given {@code (routeId, nodeId)} pair, it must never be
 * overwritten. Re-answering creates a new route, replacement node, or answer
 * revision rather than mutating this record.
 *
 * <p>Multi-select questions ({@code allowMultiSelect}) carry the full selection
 * in {@code selectedOptionIds}; {@code selectedOptionId} always mirrors the
 * FIRST selected option so every single-selection consumer keeps its existing
 * semantics unchanged.
 */
public class Answer {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID nodeId;
    private final String selectedOptionId;
    private final List<String> selectedOptionIds;
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
        this(id, projectId, routeId, nodeId, selectedOptionId,
                selectedOptionId == null ? List.of() : List.of(selectedOptionId),
                freeText, createdByUser, createdAt);
    }

    public Answer(UUID id,
                  UUID projectId,
                  UUID routeId,
                  UUID nodeId,
                  String selectedOptionId,
                  List<String> selectedOptionIds,
                  String freeText,
                  String createdByUser,
                  Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.nodeId = nodeId;
        this.selectedOptionId = selectedOptionId;
        this.selectedOptionIds = selectedOptionIds == null
                ? List.of()
                : List.copyOf(selectedOptionIds);
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

    /** Full selection for multi-select questions; single-select answers carry 0..1 entries. */
    public List<String> selectedOptionIds() {
        return selectedOptionIds;
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
