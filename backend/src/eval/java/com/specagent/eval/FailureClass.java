package com.specagent.eval;

/**
 * 文件名:FailureClass.java
 *
 * 用途:单次评测尝试的统一失败分类法。一次尝试可能带多条违例,但整体
 * 只有一个结果。分类必须是类型化枚举值——绝不在各测试里散落临时拼的自然
 * 语言字符串,保证失败统计可聚合、可比较。
 *
 * 协作:附着在 {@link Violation} 上,供 {@link EvalSummary} 汇总、
 * {@link CausalReportGenerator} 归因。
 */
public enum FailureClass {
    SCENARIO_INVALID,
    RUNTIME_INVARIANT,
    BRAIN_SCHEMA,
    FORBIDDEN_ACTION,
    REQUIRED_PROPERTY_MISSING,
    UNEXPECTED_STATE_DELTA,
    AUTHORIZATION,
    CALL_BUDGET,
    PROVIDER_FAILURE,
    CAPABILITY_FAILURE,
    JUDGE_ONLY
}
