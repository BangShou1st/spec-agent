package com.specagent.agent.decision;

import com.specagent.workspace.patch.Claim;

import java.util.List;

/**
 * Draft of an answer patch: the structured claims derived from an answer.
 */
public record AnswerPatchDraft(
        List<Claim> claims
) {
    public AnswerPatchDraft {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}