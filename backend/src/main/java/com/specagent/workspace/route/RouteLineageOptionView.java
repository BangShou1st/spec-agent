package com.specagent.workspace.route;

import com.specagent.workspace.node.NodeOption;

import java.util.UUID;

/**
 * 文件名:RouteLineageOptionView.java
 *
 * 用途:路线 lineage 节点内选项的只读视图。选项 id 由运行时持有、
 * 只读;客户端绝不在创建时把选项 id 回传给运行时,replacement 选项
 * 仅通过 label 和 impact 表达。
 */
public record RouteLineageOptionView(
        UUID id,
        String label,
        String impact) {

    public static RouteLineageOptionView from(NodeOption option) {
        return new RouteLineageOptionView(option.id(), option.label(), option.impact());
    }
}
