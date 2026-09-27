package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:NodeBodyView.java
 *
 * 用途:以 Graph 语言表示的通用节点正文。
 *
 * 约束:当前的 V1 提问工作流被投影成这个统一形状;工作流名称本身
 * 绝不进入跨语言契约(领域差异只体现在投影内容里)。
 */
public record NodeBodyView(String text, List<OptionView> options, boolean acceptsFreeText) {

    public NodeBodyView {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
