package com.specagent.workspace.route;

import com.specagent.workspace.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:RouteLineageNodeView.java
 *
 * 用途:路线 lineage 上单个不可变节点的只读视图。只暴露 UI 识别和
 * 检视分叉/重新生成之前的历史澄清节点所需的、安全的不可变节点字段;
 * 答案、补丁、上下文快照、模型负载、provider 数据和数据库内部结构
 * 一律不外露。
 */
public record RouteLineageNodeView(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<RouteLineageOptionView> options,
        boolean allowFreeAnswer,
        Instant createdAt) {

    public static RouteLineageNodeView from(Node node) {
        return new RouteLineageNodeView(
                node.id(),
                node.projectId(),
                node.parentNodeId(),
                node.supersedesNodeId(),
                node.question(),
                node.purpose(),
                node.options().stream().map(RouteLineageOptionView::from).toList(),
                node.allowFreeAnswer(),
                node.createdAt());
    }
}
