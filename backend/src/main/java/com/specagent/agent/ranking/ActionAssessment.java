package com.specagent.agent.ranking;

import com.specagent.agent.protocol.ActionFamily;

import java.util.HashSet;
import java.util.List;

/**
 * 文件名:ActionAssessment.java
 *
 * 用途:表示模型(Brain)对单个有资格执行的动作族做出的一条语义评估结果,
 * 包含适用性判断、优先级等级、理由码、证据引用和打分明细。
 *
 * 协作:由模型输出解析得到,供 SemanticRanking 聚合排序使用;
 * 紧凑构造器负责校验:理由码非空且不重复、证据引用非空且不重复、
 * "可适用"的评估必须带证据引用、打分明细不能为空。
 */
public record ActionAssessment(ActionFamily family,
                               boolean applicable,
                               RankingPriorityClass priorityClass,
                               List<RankingReasonCode> reasonCodes,
                               List<String> evidenceRefs,
                               RankingScores scores) {

    public ActionAssessment {
        if (family == null) {
            throw new IllegalArgumentException("Ranking assessment family is required");
        }
        if (priorityClass == null) {
            throw new IllegalArgumentException("Ranking assessment priority class is required");
        }
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        if (reasonCodes.stream().anyMatch(code -> code == null)
                || reasonCodes.size() != new HashSet<>(reasonCodes).size()) {
            throw new IllegalArgumentException("Ranking reason codes must be non-null and unique");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (evidenceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank())
                || evidenceRefs.size() != new HashSet<>(evidenceRefs).size()) {
            throw new IllegalArgumentException("Ranking evidence refs must be non-blank and unique");
        }
        if (applicable && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Applicable ranking assessment requires evidence refs");
        }
        if (scores == null) {
            throw new IllegalArgumentException("Ranking assessment scores are required");
        }
    }
}
