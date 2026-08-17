package com.specagent.patch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Structured requirement-state changes derived from one answer.
 *
 * <p>An answer patch carries a list of domain-neutral {@link Claim}s. Replaying
 * patches along the active route lineage derives the {@code RequirementState}.
 * The patch itself is an immutable record.
 */
public class AnswerPatch {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID sourceNodeId;
    private final UUID sourceAnswerId;
    private final List<Claim> claims;
    private final UUID createdByRunId;
    private final Instant createdAt;

    public AnswerPatch(UUID id,
                       UUID projectId,
                       UUID routeId,
                       UUID sourceNodeId,
                       UUID sourceAnswerId,
                       List<Claim> claims,
                       UUID createdByRunId,
                       Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.sourceNodeId = sourceNodeId;
        this.sourceAnswerId = sourceAnswerId;
        this.claims = claims == null ? List.of() : List.copyOf(claims);
        this.createdByRunId = createdByRunId;
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

    public UUID sourceNodeId() {
        return sourceNodeId;
    }

    public UUID sourceAnswerId() {
        return sourceAnswerId;
    }

    public List<Claim> claims() {
        return claims;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
