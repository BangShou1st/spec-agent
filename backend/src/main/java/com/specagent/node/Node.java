package com.specagent.node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A workspace unit in the exploration graph.
 *
 * <p>A node may represent an interaction (a clarification question), user- or
 * agent-authored knowledge, an external resource reference, or a generated
 * artifact. Interaction nodes keep their immutable {@code question},
 * {@code purpose}, and {@code options} fixed at creation; regeneration creates
 * a replacement node instead of mutating them. Non-interaction nodes carry
 * their payload in {@code content}.
 *
 * <p>Legacy rows created before the generic workspace model are interpreted as
 * {@code INTERACTION/QUESTION} nodes authored by the agent.
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
    private final NodeKind kind;
    private final String subtype;
    private final Map<String, Object> content;
    private final NodeAuthorKind authorKind;
    private final KnowledgeStatus knowledgeStatus;
    private final Instant retractedAt;
    private final Instant updatedAt;

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
        this(id, projectId, parentNodeId, createdByRunId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, createdAt,
                NodeKind.INTERACTION, "QUESTION", Map.of(),
                NodeAuthorKind.AGENT, null, null, createdAt);
    }

    public Node(UUID id,
                UUID projectId,
                UUID parentNodeId,
                UUID createdByRunId,
                UUID supersedesNodeId,
                String question,
                String purpose,
                List<NodeOption> options,
                boolean allowFreeAnswer,
                Instant createdAt,
                NodeKind kind,
                String subtype,
                Map<String, Object> content,
                NodeAuthorKind authorKind,
                KnowledgeStatus knowledgeStatus,
                Instant retractedAt,
                Instant updatedAt) {
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
        this.kind = kind;
        this.subtype = subtype;
        this.content = content == null ? Map.of() : Map.copyOf(content);
        this.authorKind = authorKind;
        this.knowledgeStatus = knowledgeStatus;
        this.retractedAt = retractedAt;
        this.updatedAt = updatedAt;
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

    public NodeKind kind() {
        return kind;
    }

    public String subtype() {
        return subtype;
    }

    public Map<String, Object> content() {
        return content;
    }

    /** Convenience accessor for the primary text payload inside {@code content}. */
    public String contentText() {
        Object text = content.get("text");
        return text instanceof String value && !value.isBlank() ? value : null;
    }

    public NodeAuthorKind authorKind() {
        return authorKind;
    }

    public KnowledgeStatus knowledgeStatus() {
        return knowledgeStatus;
    }

    public Instant retractedAt() {
        return retractedAt;
    }

    public boolean isRetracted() {
        return retractedAt != null;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isInteraction() {
        return kind == NodeKind.INTERACTION;
    }

    /**
     * A user-authored knowledge draft may be edited in place while it remains
     * {@code PROPOSED}. Once downstream durable history exists, semantic
     * changes must go through revision/replacement, and confirmed content
     * follows knowledge-state transitions instead of free editing.
     */
    public boolean isUserEditableDraft() {
        return !isRetracted()
                && authorKind == NodeAuthorKind.USER
                && kind == NodeKind.KNOWLEDGE
                && knowledgeStatus == KnowledgeStatus.PROPOSED;
    }
}
