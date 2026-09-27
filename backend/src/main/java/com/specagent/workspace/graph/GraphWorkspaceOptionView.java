package com.specagent.workspace.graph;

import com.specagent.workspace.node.NodeOption;

import java.util.UUID;

/**
 * 文件名:GraphWorkspaceOptionView.java
 *
 * 用途:图节点内部选项的只读视图。选项 id 由运行时生成且只读。
 * 客户端永远不会把选项 id 回传给运行时来创建答案;替换选项只能用
 * label + impact 表达。{@code recommended} 标记模型基于上下文的建议——
 * 是给用户的参考,绝不是预选答案。
 */
public record GraphWorkspaceOptionView(
        UUID id,
        String label,
        String impact,
        boolean recommended) {

    public static GraphWorkspaceOptionView from(NodeOption option) {
        return new GraphWorkspaceOptionView(option.id(), option.label(), option.impact(),
                option.recommended());
    }
}
