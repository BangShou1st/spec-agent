package com.specagent.graph;

import java.time.Instant;
import java.util.UUID;

/**
 * A semantic relation between two nodes.
 *
 * <p>Distinct from a visible continuation edge (expressed through
 * {@code nodes.parentNodeId}): semantic relations carry reasoning meaning and
 * stay out of the default Canvas. Retraction is soft and provenance is kept
 * ({@code retractedAt}); rows are never physically deleted.
 */
public class NodeRelation {

    public enum Origin { USER, AGENT, RUNTIME }

    public enum Status { ACTIVE, RETRACTED }

    private final UUID id;
    private final UUID projectId;
    private final UUID sourceNodeId;
    private final UUID targetNodeId;
    private final NodeRelationType relationType;
    private final Origin origin;
    private final Status status;
    private final UUID createdByProposalId;
    private final UUID createdByRunId;
    private final Instant createdAt;
    private final Instant retractedAt;

    public NodeRelation(UUID id,
                        UUID projectId,
                        UUID sourceNodeId,
                        UUID targetNodeId,
                        NodeRelationType relationType,
                        Origin origin,
                        Status status,
                        UUID createdByProposalId,
                        UUID createdByRunId,
                        Instant createdAt,
                        Instant retractedAt) {
        if (sourceNodeId != null && sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("A node cannot relate to itself");
        }
        this.id = id;
        this.projectId = projectId;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.relationType = relationType;
        this.origin = origin;
        this.status = status;
        this.createdByProposalId = createdByProposalId;
        this.createdByRunId = createdByRunId;
        this.createdAt = createdAt;
        this.retractedAt = retractedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID sourceNodeId() {
        return sourceNodeId;
    }

    public UUID targetNodeId() {
        return targetNodeId;
    }

    public NodeRelationType relationType() {
        return relationType;
    }

    public Origin origin() {
        return origin;
    }

    public Status status() {
        return status;
    }

    public UUID createdByProposalId() {
        return createdByProposalId;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant retractedAt() {
        return retractedAt;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
