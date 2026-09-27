package com.specagent.workspace.context;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:ContextSnapshot.java
 *
 * 用途:一次 agent 运行所用的精确世系上下文快照,交给 Brain 推理。快照由
 * {@code ContextBuilder} 从当前 route 的 tip 沿父世系回放、确定性构建,一经
 * 生成即冻结不可变,保证同一次推理可复现。兄弟 route 及被取代/归档/删除的
 * route 默认排除,并记录在 {@code excludedRouteIds} 中。它是派生上下文,不是
 * 事实源(source of truth)。
 */
public class ContextSnapshot {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID tipNodeId;
    private final ContextOperationType operationType;
    private final List<UUID> includedNodeIds;
    private final List<UUID> includedAnswerIds;
    private final List<UUID> includedPatchIds;
    private final List<UUID> excludedRouteIds;
    /**
     * 节点查询的有界一跳语义上下文:与锚点之间存在 ACTIVE 关系的另一端规范化
     * 节点 id。它们不属于世系,也绝不污染世系。
     */
    private final List<UUID> relatedNodeIds;
    /**
     * 触及锚点的 ACTIVE 语义关系(保持方向),作为节点查询的有界一跳语义上下文。
     * 其他操作类型下恒为空。
     */
    private final List<ContextRelation> relations;
    private final String specialInputs;
    private final String contextHash;
    private final Instant createdAt;

    public ContextSnapshot(UUID id,
                           UUID projectId,
                           UUID routeId,
                           UUID tipNodeId,
                           ContextOperationType operationType,
                           List<UUID> includedNodeIds,
                           List<UUID> includedAnswerIds,
                           List<UUID> includedPatchIds,
                           List<UUID> excludedRouteIds,
                           List<UUID> relatedNodeIds,
                           List<ContextRelation> relations,
                           String specialInputs,
                           String contextHash,
                           Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.tipNodeId = tipNodeId;
        this.operationType = operationType;
        this.includedNodeIds = includedNodeIds == null ? List.of() : List.copyOf(includedNodeIds);
        this.includedAnswerIds = includedAnswerIds == null ? List.of() : List.copyOf(includedAnswerIds);
        this.includedPatchIds = includedPatchIds == null ? List.of() : List.copyOf(includedPatchIds);
        this.excludedRouteIds = excludedRouteIds == null ? List.of() : List.copyOf(excludedRouteIds);
        this.relatedNodeIds = relatedNodeIds == null ? List.of() : List.copyOf(relatedNodeIds);
        this.relations = relations == null ? List.of() : List.copyOf(relations);
        this.specialInputs = specialInputs;
        this.contextHash = contextHash;
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

    public UUID tipNodeId() {
        return tipNodeId;
    }

    public ContextOperationType operationType() {
        return operationType;
    }

    public List<UUID> includedNodeIds() {
        return includedNodeIds;
    }

    public List<UUID> includedAnswerIds() {
        return includedAnswerIds;
    }

    public List<UUID> includedPatchIds() {
        return includedPatchIds;
    }

    public List<UUID> excludedRouteIds() {
        return excludedRouteIds;
    }

    public List<UUID> relatedNodeIds() {
        return relatedNodeIds;
    }

    public List<ContextRelation> relations() {
        return relations;
    }

    public String specialInputs() {
        return specialInputs;
    }

    public String contextHash() {
        return contextHash;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
