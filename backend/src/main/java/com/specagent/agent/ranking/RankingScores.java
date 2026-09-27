package com.specagent.agent.ranking;

/**
 * 文件名:RankingScores.java
 *
 * 用途:排序模型产出的有界语义维度得分,包含 blockerClosure(阻塞消除)、
 * goalProgress(目标推进)、externalNeed(外部依赖)、completionProximity(接近完成)、
 * payloadReadiness(载荷就绪)五个维度。
 *
 * 注意:每个维度取值限定在 0~2 之间,越界直接抛异常,防止模型给出失控的分数。
 */
public record RankingScores(int blockerClosure,
                            int goalProgress,
                            int externalNeed,
                            int completionProximity,
                            int payloadReadiness) {

    public RankingScores {
        validate("blockerClosure", blockerClosure);
        validate("goalProgress", goalProgress);
        validate("externalNeed", externalNeed);
        validate("completionProximity", completionProximity);
        validate("payloadReadiness", payloadReadiness);
    }

    private static void validate(String name, int value) {
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException(name + " must be between 0 and 2");
        }
    }
}
