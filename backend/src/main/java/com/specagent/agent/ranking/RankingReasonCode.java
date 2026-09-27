package com.specagent.agent.ranking;

/**
 * 文件名:RankingReasonCode.java
 *
 * 用途:封闭、有界的排序理由码枚举。模型必须从中选择理由来解释某条
 * 语义评估为什么成立,使排序结果可审计、可追溯,而不是自由发挥的自然语言解释。
 *
 * 协作:在 ActionAssessment.reasonCodes 中使用,每个理由码在该列表中必须唯一。
 */
public enum RankingReasonCode {
    MISSING_USER_INFORMATION,
    UNRESOLVED_USER_CHOICE,
    EXTERNAL_INFORMATION_REQUIRED,
    GROUNDED_ARGUMENTS_AVAILABLE,
    GOAL_SATISFIED,
    MATERIAL_NOVELTY,
    NOT_NEEDED
}
