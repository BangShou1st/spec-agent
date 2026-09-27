package com.specagent.agent.ranking;

/**
 * 文件名:RankingWeights.java
 *
 * 用途:带版本号的通用计分卡权重,决定 RankingScores 各维度在总分中的占比。
 * 权重对所有动作族一视同仁,不给任何动作族固定的优先级,保证排序的中立性。
 *
 * 协作:defaults() 提供内置默认权重;每条排序结果都会记录所用权重版本,
 * 便于事后审计。
 *
 * 注意:各权重取值限定在 0~10,越界抛异常。
 */
public record RankingWeights(String version,
                             int priorityClassWeight,
                             int blockerClosureWeight,
                             int goalProgressWeight,
                             int externalNeedWeight,
                             int completionProximityWeight,
                             int payloadReadinessWeight,
                             int evidenceWeight) {

    public static final String VERSION = "semantic-ranking-weights.v1";

    public RankingWeights {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Ranking weights version is required");
        }
        validateWeight("priorityClassWeight", priorityClassWeight);
        validateWeight("blockerClosureWeight", blockerClosureWeight);
        validateWeight("goalProgressWeight", goalProgressWeight);
        validateWeight("externalNeedWeight", externalNeedWeight);
        validateWeight("completionProximityWeight", completionProximityWeight);
        validateWeight("payloadReadinessWeight", payloadReadinessWeight);
        validateWeight("evidenceWeight", evidenceWeight);
    }

    public static RankingWeights defaults() {
        return new RankingWeights(VERSION, 1, 3, 3, 4, 2, 1, 1);
    }

    private static void validateWeight(String name, int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException(name + " must be between 0 and 10");
        }
    }
}
