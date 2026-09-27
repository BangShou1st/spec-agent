package com.specagent.workspace.graph;

import com.specagent.workspace.graph.NodeRelation;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceRelationView.java
 *
 * 用途:激活状态语义关系的只读视图。语义关系是展示在 Inspector 或
 * 可选关系图层中的推理元数据;它们绝不会被投影成画布上默认的
 * 续写连线。
 */
public record GraphWorkspaceRelationView(
        UUID id,
        UUID sourceNodeId,
        UUID targetNodeId,
        String relationType,
        String origin,
        UUID createdByProposalId,
        Instant createdAt) {

    public static GraphWorkspaceRelationView from(NodeRelation relation) {
        return new GraphWorkspaceRelationView(
                relation.id(), relation.sourceNodeId(), relation.targetNodeId(),
                relation.relationType().code(), relation.origin().name(),
                relation.createdByProposalId(), relation.createdAt());
    }
}
