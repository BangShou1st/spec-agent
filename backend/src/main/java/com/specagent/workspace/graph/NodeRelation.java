package com.specagent.workspace.graph;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:NodeRelation.java
 *
 * 用途:两个节点之间的语义关系。它不同于画布上可见的续写连线
 * (那由 {@code nodes.parentNodeId} 表达):语义关系承载推理含义,
 * 不进入默认画布。撤回是软删除且保留出处({@code retractedAt});
 * 行永远不会被物理删除。
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
