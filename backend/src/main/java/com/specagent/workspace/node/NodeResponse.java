package com.specagent.workspace.node;

import com.specagent.workspace.node.Node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:NodeResponse.java
 *
 * 用途:面向读模型与 REST 边界的安全节点表示。只暴露与未来前端
 * 相关的不可变节点数据。选项携带运行时生成的只读 id。API 绝不凭空
 * 造出节点;上游以 {@code null} 表示缺失的 tip 节点。
 */
public record NodeResponse(
        UUID id,
        UUID projectId,
        UUID parentNodeId,
        UUID supersedesNodeId,
        String question,
        String purpose,
        List<NodeOptionResponse> options,
        boolean allowFreeAnswer,
        boolean allowMultiSelect,
        Instant createdAt) {

    public static NodeResponse from(Node node) {
        return new NodeResponse(
                node.id(),
                node.projectId(),
                node.parentNodeId(),
                node.supersedesNodeId(),
                node.question(),
                node.purpose(),
                node.options().stream().map(NodeOptionResponse::from).toList(),
                node.allowFreeAnswer(),
                node.allowMultiSelect(),
                node.createdAt());
    }
}
