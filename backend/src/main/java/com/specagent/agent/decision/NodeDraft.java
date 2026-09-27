package com.specagent.agent.decision;

import com.specagent.workspace.node.NodeOption;

import java.util.List;

/**
 * 文件名:NodeDraft.java
 *
 * 用途:Agent 循环提议的澄清(clarification)节点草稿。
 */
public record NodeDraft(
        String question,
        String purpose,
        List<NodeOption> options,
        boolean allowFreeAnswer
) {
    public NodeDraft {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        purpose = purpose == null ? "" : purpose;
        options = options == null ? List.of() : List.copyOf(options);
    }
}