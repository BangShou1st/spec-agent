package com.specagent.agent.decision;

/**
 * 文件名:AgentAction.java
 *
 * 用途:定义 Agent 循环可能产出的动作的封闭集合(枚举)。动作集合刻意保持
 * 封闭:Agent 不允许发出任意字符串动作;路由(route)生命周期操作也不在其中,
 * 路由生命周期始终由运行时服务(runtime service)控制。
 */
public enum AgentAction {
    ASK_NEXT_QUESTION,
    INTERPRET_ANSWER,
    REQUEST_CONFIRMATION,
    EXPLAIN_CONFLICT,
    SUGGEST_BRANCH,
    GENERATE_SPEC,
    STOP;

    public String code() {
        return name().toLowerCase();
    }

    public static AgentAction fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent action code must not be blank");
        }
        return AgentAction.valueOf(code.toUpperCase());
    }
}