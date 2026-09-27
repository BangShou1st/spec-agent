package com.specagent.agent.ranking;

/**
 * 文件名:RankingPriorityClass.java
 *
 * 用途:模型给出的有界语义紧急程度标签。注意:它只是语义紧急度,
 * 不等于动作族的执行优先顺序(动作族先后由别处决定),排序时仅作为一个加分维度。
 *
 * 协作:作为 ActionAssessment 的一部分由模型产出,RankingScores 按其
 * semanticLevel 参与打分。
 */
public enum RankingPriorityClass {
    BLOCKING(2),
    REQUIRED_EXTERNAL_STEP(2),
    DIRECT_COMPLETION(1),
    OPTIONAL_PROGRESS(0),
    DURABLE_MATERIALIZATION(0);

    private final int semanticLevel;

    RankingPriorityClass(int semanticLevel) {
        this.semanticLevel = semanticLevel;
    }

    int semanticLevel() {
        return semanticLevel;
    }
}
