package com.specagent.agent.protocol;

import java.util.Set;

/**
 * 文件名:ClaimVocabulary.java
 *
 * 用途:claim 的封闭词表(kind 与 status 的合法取值),由旧版结构化
 * 输出解析和跨语言契约共同使用。
 *
 * 约束:此处是唯一权威来源,保证两侧对同样的未知取值做出一致的
 * fail-closed 拒绝。
 */
public final class ClaimVocabulary {

    public static final Set<String> KINDS = Set.of(
            "goal", "stakeholder", "scope", "constraint", "success_criterion",
            "output_expectation", "risk", "assumption", "open_question", "conflict", "other");

    public static final Set<String> STATUSES = Set.of(
            "confirmed", "assumed", "unresolved", "rejected");

    private ClaimVocabulary() {
    }
}
