package com.specagent.agent.decision;

import com.specagent.workspace.patch.Claim;

import java.util.List;

/**
 * 文件名:AnswerPatchDraft.java
 *
 * 用途:回答补丁(answer patch)草稿:由一条回答推导出的结构化 claims。
 */
public record AnswerPatchDraft(
        List<Claim> claims
) {
    public AnswerPatchDraft {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}