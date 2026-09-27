package com.specagent.workspace.patch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:AnswerPatch.java
 *
 * 用途:由一条答案推导出的结构化需求状态变更。一条 answer patch
 * 携带一组领域中立的 {@link Claim} 列表;沿活跃路线 lineage 重放这些
 * patch,即可推导出 {@code RequirementState}。patch 本身是不可变记录。
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
