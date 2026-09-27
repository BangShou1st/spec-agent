package com.specagent.agent.ranking;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.ActionEligibility;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:SemanticRankingSelector.java
 *
 * 用途:纯函数式的 Runtime 排序裁决器。对模型给出的语义评估做完整校验后,
 * 用权重计分卡打分并选出胜出动作族,全过程不产生副作用、不依赖模型参与打分。
 *
 * 协作:由 Runtime 在消费 Brain 的排序响应时调用,输入资格判定结果与
 * SemanticRanking,输出 SemanticRankingSelection。
 *
 * 校验规则(fail-closed):
 * - 资格判定版本、依据哈希、权重版本三者必须与排序结果完全一致;
 * - 评估列表必须恰好覆盖全部有资格的动作族,不允许出现资格外的动作族;
 * - 没有任何"可适用"的动作族时直接抛异常,而不是勉强挑选。
 *
 * 同分决胜:总分相同的情况下按枚举声明顺序(ordinal)稳定裁决,
 * 并在结果中标记 tieBreakApplied。
 */
public final class SemanticRankingSelector {

    private SemanticRankingSelector() {
    }

    public static SemanticRankingSelection select(ActionEligibility eligibility,
                                                  SemanticRanking ranking) {
        return select(eligibility, ranking, RankingWeights.defaults());
    }

    public static SemanticRankingSelection select(ActionEligibility eligibility,
                                                  SemanticRanking ranking,
                                                  RankingWeights weights) {
        if (eligibility == null || ranking == null || weights == null) {
            throw new SemanticRankingException("Ranking selection inputs are required");
        }
        if (!eligibility.version().equals(ranking.eligibilityVersion())) {
            throw new SemanticRankingException("Ranking eligibility version does not match");
        }
        if (!eligibility.basisHash().equals(ranking.eligibilityBasisHash())) {
            throw new SemanticRankingException("Ranking eligibility basis hash does not match");
        }
        if (!weights.version().equals(ranking.rankingWeightsVersion())) {
            throw new SemanticRankingException("Ranking weights version does not match");
        }

        Set<ActionFamily> eligible = new HashSet<>();
        for (String familyCode : eligibility.eligibleFamilies()) {
            eligible.add(ActionFamily.fromCode(familyCode));
        }

        Map<ActionFamily, ActionAssessment> byFamily = new EnumMap<>(ActionFamily.class);
        for (ActionAssessment assessment : ranking.assessments()) {
            if (!eligible.contains(assessment.family())) {
                throw new SemanticRankingException("Ranking assessment contains ineligible family: "
                        + assessment.family().code());
            }
            if (byFamily.put(assessment.family(), assessment) != null) {
                throw new SemanticRankingException("Duplicate ranking assessment family: "
                        + assessment.family().code());
            }
        }
        if (!byFamily.keySet().equals(eligible)) {
            Set<ActionFamily> missing = new HashSet<>(eligible);
            missing.removeAll(byFamily.keySet());
            throw new SemanticRankingException("Ranking assessment is incomplete; missing: "
                    + missing);
        }

        EnumMap<ActionFamily, RankingScoreBreakdown> scores = new EnumMap<>(ActionFamily.class);
        List<ActionFamily> applicable = new ArrayList<>();
        for (ActionFamily family : eligible) {
            ActionAssessment assessment = byFamily.get(family);
            if (!assessment.applicable()) {
                continue;
            }
            applicable.add(family);
            scores.put(family, score(assessment, weights));
        }
        if (applicable.isEmpty()) {
            throw new SemanticRankingException("Ranking has no applicable eligible family");
        }

        applicable.sort((left, right) -> {
            int scoreComparison = Integer.compare(scores.get(right).total(), scores.get(left).total());
            return scoreComparison != 0 ? scoreComparison
                    : Integer.compare(left.ordinal(), right.ordinal());
        });
        ActionFamily winner = applicable.getFirst();
        boolean tieBreak = applicable.size() > 1
                && scores.get(winner).total() == scores.get(applicable.get(1)).total();
        ActionAssessment winnerAssessment = byFamily.get(winner);
        return new SemanticRankingSelection(winner, scores.get(winner).total(), tieBreak,
                winnerAssessment.evidenceRefs(), scores);
    }

    private static RankingScoreBreakdown score(ActionAssessment assessment,
                                               RankingWeights weights) {
        RankingScores scores = assessment.scores();
        int priority = assessment.priorityClass().semanticLevel();
        int evidence = assessment.evidenceRefs().isEmpty() ? 0 : 1;
        int total = priority * weights.priorityClassWeight()
                + scores.blockerClosure() * weights.blockerClosureWeight()
                + scores.goalProgress() * weights.goalProgressWeight()
                + scores.externalNeed() * weights.externalNeedWeight()
                + scores.completionProximity() * weights.completionProximityWeight()
                + scores.payloadReadiness() * weights.payloadReadinessWeight()
                + evidence * weights.evidenceWeight();
        return new RankingScoreBreakdown(priority,
                scores.blockerClosure(), scores.goalProgress(), scores.externalNeed(),
                scores.completionProximity(), scores.payloadReadiness(), evidence, total);
    }
}
