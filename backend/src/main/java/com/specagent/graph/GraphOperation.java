package com.specagent.graph;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One typed, user-visible durable graph mutation in the append-preserving
 * operation log.
 *
 * <p>Undo is operation-specific compensation over this log; immutable
 * answers and historical lineage are never physically deleted to make the UI
 * look reverted. {@code beforeRefs}/{@code afterRefs} carry the structured
 * state each compensation/replay needs, keyed by operation type.
 */
public class GraphOperation {

    public enum Actor { USER, AGENT, SYSTEM }

    public enum Status { ACTIVE, UNDONE }

    public enum Type {
        CREATE_DRAFT_NODE(true),
        EDIT_DRAFT_NODE(true),
        APPEND_CONTINUATION(true),
        CREATE_BRANCH_AND_APPEND(true),
        ATTACH_RESOURCE(true),
        CREATE_SEMANTIC_RELATION(true),
        SET_KNOWLEDGE_STATUS(true),
        RESUME_QUESTION_FROM_HISTORY(true),
        ACCEPT_AGENT_PROPOSAL(false);

        private final boolean reversibleByDefault;

        Type(boolean reversibleByDefault) {
            this.reversibleByDefault = reversibleByDefault;
        }

        public boolean reversibleByDefault() {
            return reversibleByDefault;
        }
    }

    private final UUID id;
    private final UUID projectId;
    private final Actor actor;
    private final Type type;
    private final List<UUID> targets;
    private final Map<String, Object> beforeRefs;
    private final Map<String, Object> afterRefs;
    private final String causedBy;
    private final boolean reversible;
    private final Status status;
    private final Instant createdAt;
    private final Instant undoneAt;

    public GraphOperation(UUID id,
                          UUID projectId,
                          Actor actor,
                          Type type,
                          List<UUID> targets,
                          Map<String, Object> beforeRefs,
                          Map<String, Object> afterRefs,
                          String causedBy,
                          boolean reversible,
                          Status status,
                          Instant createdAt,
                          Instant undoneAt) {
        this.id = id;
        this.projectId = projectId;
        this.actor = actor;
        this.type = type;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.beforeRefs = beforeRefs == null ? Map.of() : Map.copyOf(beforeRefs);
        this.afterRefs = afterRefs == null ? Map.of() : Map.copyOf(afterRefs);
        this.causedBy = causedBy;
        this.reversible = reversible;
        this.status = status;
        this.createdAt = createdAt;
        this.undoneAt = undoneAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public Actor actor() {
        return actor;
    }

    public Type type() {
        return type;
    }

    public List<UUID> targets() {
        return targets;
    }

    public Map<String, Object> beforeRefs() {
        return beforeRefs;
    }

    public Map<String, Object> afterRefs() {
        return afterRefs;
    }

    public String causedBy() {
        return causedBy;
    }

    public boolean reversible() {
        return reversible;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant undoneAt() {
        return undoneAt;
    }

    public UUID targetNodeId() {
        return targets.isEmpty() ? null : targets.get(0);
    }
}
