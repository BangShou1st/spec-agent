package com.specagent.workspace.graph;

import com.specagent.workspace.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceNodeView.java
 *
 * 用途:项目图上单个工作区节点的只读视图。节点跨路线去重:共享节点
 * 只渲染一次,其路线归属由各路线的 {@code lineageNodeIds} 提供。只暴露
 * 安全的不可变节点字段;答案、patch、上下文快照、模型载荷、provider
 * 数据和数据库内部结构一律不暴露。
 *
 * 非交互节点的负载放在 {@code content};遗留问题节点仍以
 * {@code question} 作为权威正文。
 */
public record GraphWorkspaceNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<GraphWorkspaceOptionView> options,
        boolean allowFreeAnswer,
        boolean allowMultiSelect,
        Instant createdAt,
        String kind,
        String subtype,
        Map<String, Object> content,
        String authorKind,
        String knowledgeStatus,
        boolean userEditableDraft) {

    public static GraphWorkspaceNodeView from(Node node) {
        return new GraphWorkspaceNodeView(
                node.id(), node.projectId(), node.parentNodeId(), node.supersedesNodeId(),
                node.question(), node.purpose(),
                node.options().stream().map(GraphWorkspaceOptionView::from).toList(),
                node.allowFreeAnswer(), node.allowMultiSelect(), node.createdAt(),
                node.kind().code(), node.subtype(), node.content(),
                node.authorKind().code(),
                node.knowledgeStatus() == null ? null : node.knowledgeStatus().code(),
                node.isUserEditableDraft());
    }
}
