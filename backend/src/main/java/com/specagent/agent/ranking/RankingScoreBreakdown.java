package com.specagent.agent.ranking;

/**
 * 文件名:RankingScoreBreakdown.java
 *
 * 用途:可审计的打分明细向量,按各维度(priorityClass、blockerClosure、
 * goalProgress 等)逐项记录得分。
 *
 * 注意:total 是由各维度按权重推导出来的,绝不是模型直接给出的,
 * 以此保证排序分数的可信度。
 */
public record RankingScoreBreakdown(int priorityClass,
                                    int blockerClosure,
                                    int goalProgress,
                                    int externalNeed,
                                    int completionProximity,
                                    int payloadReadiness,
                                    int evidence,
                                    int total) {
}
