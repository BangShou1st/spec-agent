package com.specagent.workspace.patch;

/**
 * 文件名:ClaimKind.java
 *
 * 用途:需求 claim 的通用类型。这些类型是领域中立的需求机制,
 * 绝不允许编码具体的业务领域(软件功能、营销渠道、电商商品、
 * 课程作业等)。
 */
public enum ClaimKind {
    GOAL,
    STAKEHOLDER,
    SCOPE,
    CONSTRAINT,
    SUCCESS_CRITERION,
    OUTPUT_EXPECTATION,
    RISK,
    ASSUMPTION,
    OPEN_QUESTION,
    CONFLICT,
    OTHER;

    public String code() {
        return name().toLowerCase();
    }

    public static ClaimKind fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Claim kind code must not be null");
        }
        return ClaimKind.valueOf(code.toUpperCase());
    }
}
