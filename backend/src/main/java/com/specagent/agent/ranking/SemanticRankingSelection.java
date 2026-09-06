package com.specagent.agent.ranking;

import com.specagent.agent.contract.ActionFamily;

import java.util.List;
import java.util.Map;

/** Runtime-owned winner and score evidence for one ranking response. */
public record SemanticRankingSelection(ActionFamily winnerFamily,
                                       int winnerScore,
                                       boolean tieBreakApplied,
                                       List<String> winnerEvidenceRefs,
                                       Map<ActionFamily, RankingScoreBreakdown> scoreBreakdown) {

    public SemanticRankingSelection {
        winnerEvidenceRefs = winnerEvidenceRefs == null
                ? List.of() : List.copyOf(winnerEvidenceRefs);
        scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
    }
}
