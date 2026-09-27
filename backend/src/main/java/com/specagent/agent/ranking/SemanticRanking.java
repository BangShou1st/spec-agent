package com.specagent.agent.ranking;

import com.specagent.agent.protocol.ActionEligibility;

import java.util.HashSet;
import java.util.List;

/**
 * 文件名:SemanticRanking.java
 *
 * 用途:带版本号的模型排序结果整体表示,包含协议版本、资格判定版本与依据哈希、
 * 输入指纹、对各动作族的评估列表以及权重版本。Runtime 依据此结果选出胜出的动作。
 *
 * 协作:由 Brain 产出、Runtime 解析构造;通过校验协议/资格版本号、
 * SHA-256 指纹格式、评估列表非空且动作族不重复等,确保排序建立在
 * 与当前上下文一致的资格判定基础上。
 */
public record SemanticRanking(String protocolVersion,
                              String eligibilityVersion,
                              String eligibilityBasisHash,
                              String inputFingerprint,
                              List<ActionAssessment> assessments,
                              String rankingWeightsVersion) {

    public static final String VERSION = "agent-ranking.v1";

    public SemanticRanking {
        if (!VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("Unknown semantic ranking version: "
                    + protocolVersion);
        }
        if (!ActionEligibility.VERSION.equals(eligibilityVersion)) {
            throw new IllegalArgumentException("Unknown eligibility version: "
                    + eligibilityVersion);
        }
        validateSha256("eligibilityBasisHash", eligibilityBasisHash);
        validateSha256("inputFingerprint", inputFingerprint);
        assessments = assessments == null ? List.of() : List.copyOf(assessments);
        if (assessments.isEmpty()) {
            throw new IllegalArgumentException("Semantic ranking assessments are required");
        }
        if (assessments.stream().anyMatch(assessment -> assessment == null)) {
            throw new IllegalArgumentException("Semantic ranking assessments cannot be null");
        }
        if (assessments.stream().map(ActionAssessment::family).distinct().count()
                != assessments.size()) {
            throw new IllegalArgumentException("Duplicate semantic ranking family");
        }
        if (rankingWeightsVersion == null || rankingWeightsVersion.isBlank()) {
            throw new IllegalArgumentException("Ranking weights version is required");
        }
    }

    private static void validateSha256(String name, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
    }
}
