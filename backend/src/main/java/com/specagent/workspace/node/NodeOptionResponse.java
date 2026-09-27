package com.specagent.workspace.node;

import com.specagent.workspace.node.NodeOption;

import java.util.UUID;

/**
 * 文件名:NodeOptionResponse.java
 *
 * 用途:节点上可选项的只读表示。选项 id 由运行时生成,只读返回。
 * Phase 6.1 的 API 不允许客户端提供 {@code NodeOption} id 来创建内容。
 * {@code recommended} 标记模型基于上下文的建议。
 */
public record NodeOptionResponse(
        UUID id,
        String label,
        String impact,
        boolean recommended) {

    public static NodeOptionResponse from(NodeOption option) {
        return new NodeOptionResponse(option.id(), option.label(), option.impact(),
                option.recommended());
    }
}
