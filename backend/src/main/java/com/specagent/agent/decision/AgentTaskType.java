package com.specagent.agent.decision;

/**
 * 文件名:AgentTaskType.java
 *
 * 用途:Agent 循环可以派发给模型的任务类型的封闭集合(枚举)。
 */
public enum AgentTaskType {
    GAP_ANALYSIS,
    PLAN_NEXT_ACTION,
    DRAFT_NODE,
    INTERPRET_ANSWER,
    DRAFT_ANSWER_PATCH,
    REFLECT_NODE,
    REFLECT_PATCH,
    DRAFT_SPEC,
    GROUND_SPEC;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentTaskType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent task type code must not be blank");
        }
        return AgentTaskType.valueOf(code.toUpperCase());
    }
}