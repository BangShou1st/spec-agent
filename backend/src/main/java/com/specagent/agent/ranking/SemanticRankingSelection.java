package com.specagent.agent.ranking;

import com.specagent.agent.protocol.ActionFamily;

import java.util.List;
import java.util.Map;

/**
 * 文件名:SemanticRankingSelection.java
 *
 * 用途:Runtime 对一次排序响应的最终裁决结果:胜出的动作族、其总分、
 * 是否经过同分决胜(tieBreak)、胜出评估附带的证据引用,以及全部可适用
 * 动作族的打分明细,便于审计排序依据。
 *
 * 协作:由 SemanticRankingSelector.select 产出,供上层决策循环消费。
 */
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
